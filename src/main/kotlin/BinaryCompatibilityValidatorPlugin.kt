/*
 * Copyright 2016-2023 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

package kotlinx.validation

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import java.io.*
import kotlin.text.split

const val API_DIR = "api"
const val KLIB_PHONY_TARGET_NAME = "klib"
const val KLIB_ALL_PHONY_TARGET_NAME = "klib-all"

public class BinaryCompatibilityValidatorPlugin : Plugin<Project> {

    @ExperimentalLibraryAbiReader
    override fun apply(target: Project): Unit = with(target) {
        val extension = extensions.create("apiValidation", ApiValidationExtension::class.java)
        validateExtension(extension)
        allprojects {
            configureProject(it, extension)
        }
    }

    @ExperimentalLibraryAbiReader
    private fun Project.validateExtension(extension: ApiValidationExtension) {
        afterEvaluate {
            val ignored = extension.ignoredProjects
            val all = allprojects.map { it.name }
            for (project in ignored) {
                require(project in all) { "Cannot find excluded project $project in all projects: $all" }
            }
            if (extension.klib.enabled) {
                try {
                    LibraryAbiReader.javaClass
                } catch (e: NoClassDefFoundError) {
                    throw IllegalStateException(
                        "KLib validation is not available. " +
                                "Make sure the project use at least Kotlin 1.9.20.", e
                    )
                }
            }
        }
    }

    private fun configureProject(project: Project, extension: ApiValidationExtension) {
        configureKotlinPlugin(project, extension)
        configureAndroidPlugin(project, extension)
        configureMultiplatformPlugin(project, extension)
    }

    private fun configurePlugin(
        name: String,
        project: Project,
        extension: ApiValidationExtension,
        action: Action<AppliedPlugin>
    ) = project.pluginManager.withPlugin(name) {
        if (project.name in extension.ignoredProjects) return@withPlugin
        action.execute(it)
    }

    private fun configureMultiplatformPlugin(
        project: Project,
        extension: ApiValidationExtension
    ) = configurePlugin("kotlin-multiplatform", project, extension) {
        if (project.name in extension.ignoredProjects) return@configurePlugin
        val kotlin = project.kotlinMultiplatform

        // Create common tasks for multiplatform
        val commonApiDump = project.tasks.register("apiDump") {
            it.group = "other"
            it.description = "Task that collects all target specific dump tasks"
        }

        val commonApiCheck: TaskProvider<Task> = project.tasks.register("apiCheck") {
            it.group = "verification"
            it.description = "Shortcut task that depends on all specific check tasks"
        }.apply { project.tasks.named("check") { it.dependsOn(this) } }

        val jvmTargetCountProvider = project.provider {
            kotlin.targets.count {
                it.platformType in arrayOf(
                    KotlinPlatformType.jvm,
                    KotlinPlatformType.androidJvm
                )
            }
        }

        val jvmDirConfig = jvmTargetCountProvider.map {
            if (it == 1) DirConfig.COMMON else DirConfig.TARGET_DIR
        }
        val klibDirConfig = project.provider { DirConfig.COMMON }

        kotlin.targets.matching { it.jvmBased }.all { target ->
            val targetConfig = TargetConfig(project, extension, target.name, jvmDirConfig)
            if (target.platformType == KotlinPlatformType.jvm) {
                target.mainCompilations.all {
                    project.configureKotlinCompilation(it, extension, targetConfig, commonApiDump, commonApiCheck)
                }
            } else if (target.platformType == KotlinPlatformType.androidJvm) {
                target.compilations.matching { it.name == "release" }.all {
                    project.configureKotlinCompilation(
                        it,
                        extension,
                        targetConfig,
                        commonApiDump,
                        commonApiCheck,
                        useOutput = true
                    )
                }
            }
        }
        KlibValidationPipelineBuilder(klibDirConfig, extension).configureTasks(project, commonApiDump, commonApiCheck)
    }

    private fun configureAndroidPlugin(
        project: Project,
        extension: ApiValidationExtension
    ) {
        configureAndroidPluginForKotlinLibrary(project, extension)

    }

    private fun configureAndroidPluginForKotlinLibrary(
        project: Project,
        extension: ApiValidationExtension
    ) = configurePlugin("kotlin-android", project, extension) {
        val androidExtension = project.extensions
            .getByName("kotlin") as KotlinAndroidProjectExtension
        androidExtension.target.compilations.matching {
            it.compilationName == "release"
        }.all {
            project.configureKotlinCompilation(it, extension, useOutput = true)
        }
    }

    private fun configureKotlinPlugin(
        project: Project,
        extension: ApiValidationExtension
    ) = configurePlugin("kotlin", project, extension) {
        project.configureApiTasks(extension, TargetConfig(project, extension))
    }
}

private class TargetConfig constructor(
    project: Project,
    extension: ApiValidationExtension,
    val targetName: String? = null,
    dirConfig: Provider<DirConfig>? = null,
) {
    private val apiDirProvider = project.provider {
        val dir = extension.apiDumpDirectory

        val root = project.layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()
        val resolvedDir = root.resolve(dir).normalize()
        if (!resolvedDir.startsWith(root)) {
            throw IllegalArgumentException(
                "apiDumpDirectory (\"$dir\") should be inside the project directory, " +
                        "but it resolves to a path outside the project root.\n" +
                        "Project's root path: $root\nResolved apiDumpDirectory: $resolvedDir"
            )
        }

        dir
    }

    val apiDir = dirConfig?.map { dirConfig ->
        when (dirConfig) {
            DirConfig.COMMON -> apiDirProvider.get()
            else -> "${apiDirProvider.get()}/$targetName"
        }
    } ?: apiDirProvider

    fun apiTaskName(suffix: String) = when (targetName) {
        null, "" -> "api$suffix"
        else -> "${targetName}Api$suffix"
    }
}

private enum class DirConfig {
    /**
     * `api` directory for .api files.
     * Used in single target projects
     */
    COMMON,

    /**
     * Target-based directory, used in multitarget setups.
     * E.g. for the project with targets jvm and android,
     * the resulting paths will be
     * `/api/jvm/project.api` and `/api/android/project.api`
     */
    TARGET_DIR
}

private fun Project.configureKotlinCompilation(
    compilation: KotlinCompilation<KotlinCommonOptions>,
    extension: ApiValidationExtension,
    targetConfig: TargetConfig = TargetConfig(this, extension),
    commonApiDump: TaskProvider<Task>? = null,
    commonApiCheck: TaskProvider<Task>? = null,
    useOutput: Boolean = false,
) {
    val projectName = project.name
    val dumpFileName = project.jvmDumpFileName
    val apiDirProvider = targetConfig.apiDir
    val apiBuildDir = apiDirProvider.map { layout.buildDirectory.asFile.get().resolve(it) }

    val apiBuild = task<KotlinApiBuildTask>(targetConfig.apiTaskName("Build")) {
        // Do not enable task for empty umbrella modules
        isEnabled =
            apiCheckEnabled(
                projectName,
                extension
            ) && compilation.allKotlinSourceSets.any { it.kotlin.srcDirs.any { it.exists() } }
        // 'group' is not specified deliberately, so it will be hidden from ./gradlew tasks
        description =
            "Builds Kotlin API for 'main' compilations of $projectName. Complementary task and shouldn't be called manually"
        if (useOutput) {
            // Workaround for #4
            inputClassesDirs =
                files(provider<Any> { if (isEnabled) compilation.output.classesDirs else emptyList<Any>() })
            inputDependencies =
                files(provider<Any> { if (isEnabled) compilation.output.classesDirs else emptyList<Any>() })
        } else {
            inputClassesDirs =
                files(provider<Any> { if (isEnabled) compilation.output.classesDirs else emptyList<Any>() })
            inputDependencies =
                files(provider<Any> { if (isEnabled) compilation.compileDependencyFiles else emptyList<Any>() })
        }
        outputApiFile = apiBuildDir.get().resolve(dumpFileName)
    }
    configureCheckTasks(apiBuildDir, apiBuild, extension, targetConfig, commonApiDump, commonApiCheck)
}

internal val Project.sourceSets: SourceSetContainer
    get() = extensions.getByName("sourceSets") as SourceSetContainer

internal val Project.apiValidationExtensionOrNull: ApiValidationExtension?
    get() =
        generateSequence(this) { it.parent }
            .map { it.extensions.findByType(ApiValidationExtension::class.java) }
            .firstOrNull { it != null }

private fun apiCheckEnabled(projectName: String, extension: ApiValidationExtension): Boolean =
    projectName !in extension.ignoredProjects && !extension.validationDisabled

fun klibAbiCheckEnabled(projectName: String, extension: ApiValidationExtension): Boolean =
    projectName !in extension.ignoredProjects && !extension.validationDisabled && extension.klib.enabled

private fun Project.configureApiTasks(
    extension: ApiValidationExtension,
    targetConfig: TargetConfig = TargetConfig(this, extension),
) {
    val projectName = project.name
    val dumpFileName = project.jvmDumpFileName
    val apiBuildDir = targetConfig.apiDir.map { layout.buildDirectory.asFile.get().resolve(it) }
    val sourceSetsOutputsProvider = project.provider {
        sourceSets
            .filter { it.name == SourceSet.MAIN_SOURCE_SET_NAME || it.name in extension.additionalSourceSets }
            .map { it.output.classesDirs }
    }

    val apiBuild = task<KotlinApiBuildTask>(targetConfig.apiTaskName("Build")) {
        isEnabled = apiCheckEnabled(projectName, extension)
        // 'group' is not specified deliberately, so it will be hidden from ./gradlew tasks
        description =
            "Builds Kotlin API for 'main' compilations of $projectName. Complementary task and shouldn't be called manually"
        inputClassesDirs = files(provider<Any> { if (isEnabled) sourceSetsOutputsProvider.get() else emptyList<Any>() })
        inputDependencies =
            files(provider<Any> { if (isEnabled) sourceSetsOutputsProvider.get() else emptyList<Any>() })
        outputApiFile = apiBuildDir.get().resolve(dumpFileName)
    }

    configureCheckTasks(apiBuildDir, apiBuild, extension, targetConfig)
}

private fun Project.configureCheckTasks(
    apiBuildDir: Provider<File>,
    apiBuild: TaskProvider<*>,
    extension: ApiValidationExtension,
    targetConfig: TargetConfig,
    commonApiDump: TaskProvider<Task>? = null,
    commonApiCheck: TaskProvider<Task>? = null,
) {
    val projectName = project.name
    val apiCheckDir = targetConfig.apiDir.map {
        projectDir.resolve(it).also { r ->
            logger.debug("Configuring api for ${targetConfig.targetName ?: "jvm"} to $r")
        }
    }
    val apiCheck = task<KotlinApiCompareTask>(targetConfig.apiTaskName("Check")) {
        isEnabled = apiCheckEnabled(projectName, extension) && apiBuild.map { it.enabled }.getOrElse(true)
        group = "verification"
        description = "Checks signatures of public API against the golden value in API folder for $projectName"
        projectApiFile = apiCheckDir.get().resolve(jvmDumpFileName)
        generatedApiFile = apiBuildDir.get().resolve(jvmDumpFileName)
        dependsOn(apiBuild)
    }

    val dumpFileName = project.jvmDumpFileName
    val apiDump = task<CopyFile>(targetConfig.apiTaskName("Dump")) {
        isEnabled = apiCheckEnabled(projectName, extension) && apiBuild.map { it.enabled }.getOrElse(true)
        group = "other"
        description = "Syncs API from build dir to ${targetConfig.apiDir} dir for $projectName"
        from = apiBuildDir.get().resolve(dumpFileName)
        to = apiCheckDir.get().resolve(dumpFileName)
        dependsOn(apiBuild)
    }

    commonApiDump?.configure { it.dependsOn(apiDump) }

    when (commonApiCheck) {
        null -> project.tasks.named("check").configure { it.dependsOn(apiCheck) }
        else -> commonApiCheck.configure { it.dependsOn(apiCheck) }
    }
}

private inline fun <reified T : Task> Project.task(
    name: String,
    noinline configuration: T.() -> Unit,
): TaskProvider<T> = tasks.register(name, T::class.java, Action(configuration))

const val BANNED_TARGETS_PROPERTY_NAME = "binary.compatibility.validator.klib.targets.blacklist.for.testing"

private class KlibValidationPipelineBuilder(
    val dirConfig: Provider<DirConfig>?,
    val extension: ApiValidationExtension
) {
    lateinit var intermediateFilesConfig: Provider<DirConfig>

    fun configureTasks(project: Project, commonApiDump: TaskProvider<Task>, commonApiCheck: TaskProvider<Task>) {
        // In the intermediate phase of Klib dump generation there are always multiple targets, thus we need
        // target-based directory tree.
        intermediateFilesConfig = project.provider { DirConfig.TARGET_DIR }
        val klibApiDirConfig = dirConfig?.map { TargetConfig(project, KLIB_PHONY_TARGET_NAME, dirConfig) }
        val klibDumpConfig = TargetConfig(project, KLIB_PHONY_TARGET_NAME, intermediateFilesConfig)
        val klibDumpAllConfig = TargetConfig(project, KLIB_ALL_PHONY_TARGET_NAME, intermediateFilesConfig)

        val projectDir = project.projectDir
        val klibApiDir = klibApiDirConfig?.map {
            projectDir.resolve(it.apiDir.get())
        }!!
        val klibMergeDir = project.buildDir.resolve(klibDumpConfig.apiDir.get())
        val klibMergeAllDir = project.buildDir.resolve(klibDumpAllConfig.apiDir.get())
        val klibExtractedFileDir = klibMergeAllDir.resolve("extracted")

        val klibMerge = project.mergeKlibsUmbrellaTask(klibDumpConfig, klibMergeDir)
        val klibMergeAll = project.mergeAllKlibsUmbrellaTask(klibDumpConfig, klibMergeAllDir)
        val klibDump = project.dumpKlibsTask(klibDumpConfig, klibApiDir, klibMergeAllDir)
        val klibExtractAbiForSupportedTargets = project.extractAbi(klibDumpConfig, klibApiDir, klibExtractedFileDir)
        val klibCheck = project.checkKlibsTask(klibDumpConfig, project.provider { klibExtractedFileDir }, klibMergeDir)

        commonApiDump.configure { it.dependsOn(klibDump) }
        commonApiCheck.configure { it.dependsOn(klibCheck) }

        klibDump.configure { it.dependsOn(klibMergeAll) }
        klibCheck.configure {
            it.dependsOn(klibExtractAbiForSupportedTargets)
            it.dependsOn(klibMerge)
        }

        project.configureTargets(klibApiDir, klibMerge, klibMergeAll)
    }

    private fun Project.checkKlibsTask(
        klibDumpConfig: TargetConfig,
        klibApiDir: Provider<File>,
        klibMergeDir: File
    ) = project.task<KotlinApiCompareTask>(klibDumpConfig.apiTaskName("Check")) {
        isEnabled = klibAbiCheckEnabled(project.name, extension)
        group = "verification"
        description = "Checks signatures of public klib ABI against the golden value in ABI folder for " +
                project.name
        projectApiFile = klibApiDir.get().resolve(klibDumpFileName)
        generatedApiFile = klibMergeDir.resolve(klibDumpFileName)
    }

    private fun Project.dumpKlibsTask(
        klibDumpConfig: TargetConfig,
        klibApiDir: Provider<File>,
        klibMergeDir: File
    ) = project.task<CopyFile>(klibDumpConfig.apiTaskName("Dump")) {
        isEnabled = klibAbiCheckEnabled(project.name, extension)
        description = "Syncs klib ABI dump from build dir to ${klibDumpConfig.apiDir} dir for ${project.name}"
        group = "other"
        from = klibMergeDir.resolve(klibDumpFileName)
        to = klibApiDir.get().resolve(klibDumpFileName)
    }

    private fun Project.extractAbi(
        klibDumpConfig: TargetConfig,
        klibApiDir: Provider<File>,
        klibOutputDir: File
    ) = project.task<KotlinKlibExtractSupportedTargetsAbiTask>(klibDumpConfig.apiTaskName("PrepareAbiForValidation")) {
        isEnabled = klibAbiCheckEnabled(project.name, extension)
        description = "Prepare a reference ABI file by removing all unsupported targets from it"
        group = "other"
        strictValidation = extension.klib.strictValidation
        groupTargetNames = extension.klib.useTargetGroupAliases
        targets = supportedTargets()
        inputAbiFile = klibApiDir.get().resolve(klibDumpFileName)
        outputAbiFile = klibOutputDir.resolve(klibDumpFileName)
    }

    private fun Project.mergeAllKlibsUmbrellaTask(
        klibDumpConfig: TargetConfig,
        klibMergeDir: File,
    ) = project.task<KotlinKlibMergeAbiTask>(
        klibDumpConfig.apiTaskName("MergeAll")
    )
    {
        isEnabled = klibAbiCheckEnabled(project.name, extension)
        description = "Merges multiple klib ABI dump files generated for " +
                "different targets (including files substituting dumps for unsupported target) " +
                "into a single multi-target dump"
        dumpFileName = klibDumpFileName
        mergedFile = klibMergeDir.resolve(klibDumpFileName)
        groupTargetNames = extension.klib.useTargetGroupAliases
    }

    private fun Project.mergeKlibsUmbrellaTask(
        klibDumpConfig: TargetConfig,
        klibMergeDir: File
    ) = project.task<KotlinKlibMergeAbiTask>(klibDumpConfig.apiTaskName("Merge")) {
        isEnabled = klibAbiCheckEnabled(project.name, extension)
        description = "Merges multiple klib ABI dump files generated for " +
                "different targets into a single multi-target dump"
        dumpFileName = klibDumpFileName
        mergedFile = klibMergeDir.resolve(klibDumpFileName)
        groupTargetNames = extension.klib.useTargetGroupAliases
    }

    fun Project.bannedTargets(): Set<String> {
        val prop = project.properties[BANNED_TARGETS_PROPERTY_NAME] as String?
        prop ?: return emptySet()
        return prop.split(",").map { it.trim() }.toSet().also {
            if (it.isNotEmpty()) {
                project.logger.warn(
                    "WARNING: Following property is not empty: $BANNED_TARGETS_PROPERTY_NAME. " +
                            "If you're don't know what it means, please make sure that its value is empty."
                )
            }
        }
    }

    fun Project.configureTargets(
        klibApiDir: Provider<File>,
        mergeTask: TaskProvider<KotlinKlibMergeAbiTask>,
        mergeFakeTask: TaskProvider<KotlinKlibMergeAbiTask>
    ) {
        val kotlin = project.kotlinMultiplatform

        val supportedTargets = supportedTargets()
        kotlin.targets.matching { it.emitsKlib }.configureEach { currentTarget ->
            val mainCompilations = currentTarget.mainCompilations
            if (mainCompilations.none()) {
                return@configureEach
            }

            val targetName = currentTarget.targetName
            val targetConfig = TargetConfig(project, targetName, intermediateFilesConfig)
            val apiBuildDir = targetConfig.apiDir.map { project.buildDir.resolve(it) }.get()

            val targetSupported = targetName in supportedTargets.get()
            if (targetSupported) {
                mainCompilations.all {
                    val buildTargetAbi = configureKlibCompilation(
                        it, extension, targetConfig,
                        apiBuildDir
                    )
                    mergeTask.configure {
                        it.addInput(targetName, apiBuildDir)
                        it.dependsOn(buildTargetAbi)
                    }
                    mergeFakeTask.configure {
                        it.addInput(targetName, apiBuildDir)
                        it.dependsOn(buildTargetAbi)
                    }
                }
                return@configureEach
            }

            val unsupportedTargetStub = mergeDependencyForUnsupportedTarget(targetConfig)
            mergeTask.configure {
                it.dependsOn(unsupportedTargetStub)
            }
            val proxy = unsupportedTargetDumpProxy(klibApiDir, targetConfig, apiBuildDir, supportedTargets.get())
            mergeFakeTask.configure {
                it.addInput(targetName, apiBuildDir)
                it.dependsOn(proxy)
            }
        }
        mergeTask.configure {
            it.doFirst {
                if (supportedTargets.get().isEmpty()) {
                    throw IllegalStateException(
                        "KLib ABI dump/validation requires at least enabled klib target, but none were found."
                    )
                }
            }
        }
    }

    private fun Project.supportedTargets(): Provider<Set<String>> {
        val banned = bannedTargets()
        return project.provider {
            val hm = HostManager()
            project.kotlinMultiplatform.targets.matching { it.emitsKlib }
                .asSequence()
                .filter {
                    if (it is KotlinNativeTarget) {
                        hm.isEnabled(it.konanTarget) && it.targetName !in banned
                    } else {
                        true
                    }
                }
                .map {
                    it.targetName
                }.toSet()
        }
    }


    private fun Project.configureKlibCompilation(
        compilation: KotlinCompilation<KotlinCommonOptions>,
        extension: ApiValidationExtension,
        targetConfig: TargetConfig,
        apiBuildDir: File
    ): TaskProvider<KotlinKlibAbiBuildTask> {
        val projectName = project.name
        val buildTask = project.task<KotlinKlibAbiBuildTask>(targetConfig.apiTaskName("Build")) {
            target = targetConfig.targetName!!
            // Do not enable task for empty umbrella modules
            isEnabled =
                klibAbiCheckEnabled(
                    projectName,
                    extension
                ) && compilation.allKotlinSourceSets.any { it.kotlin.srcDirs.any { it.exists() } }
            // 'group' is not specified deliberately, so it will be hidden from ./gradlew tasks
            description = "Builds Kotlin KLib ABI for 'main' compilations of $projectName. " +
                    "Complementary task and shouldn't be called manually"
            klibFile = project.files(project.provider { compilation.output.classesDirs })
            compilationDependencies = project.files(project.provider { compilation.compileDependencyFiles })
            signatureVersion = extension.klib.signatureVersion
            outputApiFile = apiBuildDir.resolve(klibDumpFileName)
        }
        return buildTask
    }

    private fun Project.mergeDependencyForUnsupportedTarget(targetConfig: TargetConfig): TaskProvider<DefaultTask> {
        return project.task<DefaultTask>(targetConfig.apiTaskName("Build")) {
            isEnabled = apiCheckEnabled(project.name, extension)

            doLast {
                logger.warn(
                    "Target ${targetConfig.targetName} is not supported by the host compiler and the " +
                            "KLib ABI dump could not be generated for it."
                )
            }
        }
    }

    private fun Project.unsupportedTargetDumpProxy(
        klibApiDir: Provider<File>,
        targetConfig: TargetConfig, apiBuildDir: File,
        supportedTargets: Set<String>
    ): TaskProvider<KotlinKlibInferAbiForUnsupportedTargetTask> {
        val targetName = targetConfig.targetName!!
        return project.task<KotlinKlibInferAbiForUnsupportedTargetTask>(targetConfig.apiTaskName("InferAbiDump")) {
            isEnabled = klibAbiCheckEnabled(project.name, extension)
            description = "Try to replace the dump for unsupported target $targetName with the dump " +
                    "generated for one of the supported targets."
            group = "other"
            this.supportedTargets = supportedTargets
            inputImageFile = klibApiDir.get().resolve(klibDumpFileName)
            outputApiDir = apiBuildDir.toString()
            outputFile = apiBuildDir.resolve(klibDumpFileName)
            unsupportedTarget = targetConfig.targetName
            dumpFileName = klibDumpFileName
            dependsOn(project.tasks.withType(KotlinKlibAbiBuildTask::class.java))
        }
    }
}

private val KotlinTarget.emitsKlib: Boolean
    get() {
        val platformType = this.platformType
        return platformType == KotlinPlatformType.native ||
                platformType == KotlinPlatformType.wasm ||
                platformType == KotlinPlatformType.js
    }

private val KotlinTarget.jvmBased: Boolean
    get() {
        val platformType = this.platformType
        return platformType == KotlinPlatformType.jvm || platformType == KotlinPlatformType.androidJvm
    }

private val Project.kotlinMultiplatform
    get() = extensions.getByName("kotlin") as KotlinMultiplatformExtension

private val KotlinTarget.mainCompilations
    get() = compilations.matching { it.name == "main" }

private val Project.jvmDumpFileName: String
    get() = "$name.api"
private val Project.klibDumpFileName: String
    get() = "$name.klib.api"
