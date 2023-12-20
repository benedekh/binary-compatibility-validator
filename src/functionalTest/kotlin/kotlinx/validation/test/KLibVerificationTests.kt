/*
 * Copyright 2016-2023 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

package kotlinx.validation.test

import kotlinx.validation.BANNED_TARGETS_PROPERTY_NAME
import kotlinx.validation.KLIB_PHONY_TARGET_NAME
import kotlinx.validation.api.*
import kotlinx.validation.api.buildGradleKts
import kotlinx.validation.api.resolve
import kotlinx.validation.api.test
import org.assertj.core.api.Assertions
import org.gradle.testkit.runner.BuildResult
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.junit.Assume
import org.junit.Test
import kotlin.test.assertTrue

private fun KLibVerificationTests.checkKlibDump(buildResult: BuildResult, expectedDumpFileName: String,
                                                projectName: String = "testproject",
                                                dumpTask: String = ":apiDump") {
    buildResult.assertTaskSuccess(dumpTask)

    val generatedDump = rootProjectAbiDump(target = KLIB_PHONY_TARGET_NAME, project = projectName)
    assertTrue(generatedDump.exists(), "There are no dumps generated for KLibs")

    val expected = readFileList(expectedDumpFileName)

    Assertions.assertThat(generatedDump.readText()).isEqualToIgnoringNewLines(expected)
}

internal class KLibVerificationTests : BaseKotlinGradleTest() {
    @Test
    fun `apiDump for native targets`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/TopLevelDeclarations.klib.dump")
    }

    @Test
    fun `apiCheck for native targets`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }

            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {
                resolve("examples/classes/TopLevelDeclarations.klib.dump")
            }

            runner {
                arguments.add(":apiCheck")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":apiCheck")
        }
    }

    @Test
    fun `apiCheck for native targets should fail when a class is not in a dump`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
            }
            kotlin("BuildConfig.kt", "commonMain") {
                resolve("examples/classes/BuildConfig.kt")
            }

            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {}

            runner {
                arguments.add(":apiCheck")
            }
        }

        runner.buildAndFail().apply {

            Assertions.assertThat(output).contains("+final class com.company/BuildConfig { // com.company/BuildConfig|null[0]")
            tasks.filter { it.path.endsWith("ApiCheck") }
                .forEach {
                    assertTaskFailure(it.path)
                }
        }
    }

    @Test
    fun `apiDump should include target-specific sources`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
            }
            kotlin("AnotherBuildConfig.kt", "commonMain") {
                resolve("examples/classes/AnotherBuildConfig.kt")
            }
            kotlin("AnotherBuildConfigLinuxArm64.kt", "linuxArm64Main") {
                resolve("examples/classes/AnotherBuildConfigLinuxArm64.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":apiDump")

            // not common, but built from the common source set
            val dump = rootProjectAbiDump(KLIB_PHONY_TARGET_NAME, "testproject")
            assertTrue(dump.exists(), "Dump does not exist")

            val expectedDump = readFileList("examples/classes/AnotherBuildConfigLinuxArm64Extra.klib.dump")
            Assertions.assertThat(dump.readText()).isEqualToIgnoringNewLines(expectedDump)
        }
    }

    @Test
    fun `apiDump with native targets along with JVM target`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/base/enableJvmInWithNativePlugin.gradle.kts")
            }
            kotlin("AnotherBuildConfig.kt", "commonMain") {
                resolve("examples/classes/AnotherBuildConfig.kt")
            }
            runner {
                arguments.add(":apiDump")
            }
        }

        runner.build().apply {
            checkKlibDump(this, "examples/classes/AnotherBuildConfig.klib.dump")

            val jvmApiDump = rootProjectAbiDump("jvm", "testproject")
            assertTrue(jvmApiDump.exists(), "No API dump for JVM")

            val jvmExpected = readFileList("examples/classes/AnotherBuildConfig.dump")
            Assertions.assertThat(jvmApiDump.readText()).isEqualToIgnoringNewLines(jvmExpected)
        }
    }

    @Test
    fun `apiDump should ignore a class listed in ignoredClasses`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/ignoredClasses/oneValidFullyQualifiedClass.gradle.kts")
            }
            kotlin("BuildConfig.kt", "commonMain") {
                resolve("examples/classes/BuildConfig.kt")
            }
            kotlin("AnotherBuildConfig.kt", "commonMain") {
                resolve("examples/classes/AnotherBuildConfig.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/AnotherBuildConfig.klib.dump")
    }

    @Test
    fun `apiDump should succeed if a class listed in ignoredClasses is not found`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/ignoredClasses/oneValidFullyQualifiedClass.gradle.kts")
            }
            kotlin("AnotherBuildConfig.kt", "commonMain") {
                resolve("examples/classes/AnotherBuildConfig.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/AnotherBuildConfig.klib.dump")
    }

    @Test
    fun `apiDump should ignore all entities from a package listed in ingoredPackages`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/ignoredPackages/oneValidPackage.gradle.kts")
            }
            kotlin("BuildConfig.kt", "commonMain") {
                resolve("examples/classes/BuildConfig.kt")
            }
            kotlin("AnotherBuildConfig.kt", "commonMain") {
                resolve("examples/classes/AnotherBuildConfig.kt")
            }
            kotlin("SubPackage.kt", "commonMain") {
                resolve("examples/classes/SubPackage.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/AnotherBuildConfig.klib.dump")
    }

    @Test
    fun `apiDump should ignore all entities annotated with non-public markers`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/nonPublicMarkers/klib.gradle.kts")
            }
            kotlin("HiddenDeclarations.kt", "commonMain") {
                resolve("examples/classes/HiddenDeclarations.kt")
            }
            kotlin("NonPublicMarkers.kt", "commonMain") {
                resolve("examples/classes/NonPublicMarkers.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/HiddenDeclarations.klib.dump")
    }

    @Test
    fun `apiDump should not dump subclasses excluded via ignoredClasses`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/ignoreSubclasses/ignore.gradle.kts")
            }
            kotlin("Subclasses.kt", "commonMain") {
                resolve("examples/classes/Subclasses.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/Subclasses.klib.dump")
    }

    @Test
    fun `apiCheck for native targets using v1 signatures`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/signatures/v1.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }

            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {
                resolve("examples/classes/TopLevelDeclarations.klib.v1.dump")
            }

            runner {
                arguments.add(":apiCheck")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":apiCheck")
        }
    }

    @Test
    fun `apiDump for native targets should fail when using invalid signature version`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/signatures/invalid.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }

            runner {
                arguments.add(":apiDump")
            }
        }

        runner.buildAndFail()
    }

    @Test
    fun `apiDump should work for Apple-targets`() {
        Assume.assumeTrue(HostManager().isEnabled(KonanTarget.MACOS_ARM64))
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/appleTargets/targets.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            runner {
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/TopLevelDeclarations.klib.all.dump")
    }

    @Test
    fun `apiCheck should work for Apple-targets`() {
        Assume.assumeTrue(HostManager().isEnabled(KonanTarget.MACOS_ARM64))
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/appleTargets/targets.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {
                resolve("examples/classes/TopLevelDeclarations.klib.all.dump")
            }
            runner {
                arguments.add(":apiCheck")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":apiCheck")
        }
    }

    @Test
    fun `apiDump should fail if a target is not supported`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":apiDump")
            }
        }

        runner.buildAndFail()
    }

    @Test
    fun `apiDump should not fail if a target is not supported and ignore flag is set`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":apiDump")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/TopLevelDeclarations.klib.unsup.dump")
    }

    @Test
    fun `apiCheck should fail if a target is not supported`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":apiCheck")
            }
        }

        runner.buildAndFail()
    }

    @Test
    fun `apiCheck should ignore unsupported target when the ignore flag is set`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {
                // note that the regular dump is used, where linuxArm64 is presented
                resolve("examples/classes/TopLevelDeclarations.klib.dump")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":apiCheck")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":apiCheck")
        }
    }

    @Test
    fun `klibDumpAll should replace dump for unsupported target with dump for similar target`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":klibApiDumpAll")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/TopLevelDeclarations.klib.dump",
            dumpTask = ":klibApiDumpAll")
    }

    @Test
    fun `klibCheckAll should pass if there is a target with similar sourceSet`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            abiFile(projectName = "testproject", target = KLIB_PHONY_TARGET_NAME) {
                resolve("examples/classes/TopLevelDeclarations.klib.dump")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":klibApiCheckAll")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":klibApiCheckAll")
        }
    }

    @Test
    fun `klibDumpAll should fail if no targets share exact sourceSets with the disabled target`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            kotlin("AnotherBuildConfigLinuxArm64.kt", "linuxArm64Main") {
                resolve("examples/classes/AnotherBuildConfigLinuxArm64.kt")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":klibApiDumpAll")
            }
        }

        runner.buildAndFail().apply {
            assertTaskFailure(":linuxArm64ApiFakeAbiDump")
        }
    }

    @Test
    fun `klibCheckAll should fail if no targets share exact sourceSets with the disabled target`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            kotlin("AnotherBuildConfigLinuxArm64.kt", "linuxArm64Main") {
                resolve("examples/classes/AnotherBuildConfigLinuxArm64.kt")
            }
            abiFile("testproject", KLIB_PHONY_TARGET_NAME) {
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":klibApiCheckAll")
            }
        }

        runner.buildAndFail().apply {
            assertTaskFailure(":linuxArm64ApiFakeAbiDump")
        }
    }


    @Test
    fun `klibDumpAll should with similar enough target when allowed`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/inexact.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            kotlin("AnotherBuildConfigLinuxArm64.kt", "linuxArm64Main") {
                resolve("examples/classes/AnotherBuildConfigLinuxArm64.kt")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":klibApiDumpAll")
            }
        }

        checkKlibDump(runner.build(), "examples/classes/TopLevelDeclarations.klib.dump",
            dumpTask = ":klibApiDumpAll")
    }

    @Test
    fun `klibCheckAll should with similar enough target when allowed`() {
        val runner = test {
            settingsGradleKts {
                resolve("examples/gradle/settings/settings-name-testproject.gradle.kts")
            }
            buildGradleKts {
                resolve("examples/gradle/base/withNativePlugin.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/ignore.gradle.kts")
                resolve("examples/gradle/configuration/unsupported/inexact.gradle.kts")
            }
            kotlin("TopLevelDeclarations.kt", "commonMain") {
                resolve("examples/classes/TopLevelDeclarations.kt")
            }
            kotlin("AnotherBuildConfigLinuxArm64.kt", "linuxArm64Main") {
                resolve("examples/classes/AnotherBuildConfigLinuxArm64.kt")
            }
            abiFile("testproject", KLIB_PHONY_TARGET_NAME) {
                resolve("examples/classes/TopLevelDeclarations.klib.dump")
            }
            runner {
                arguments.add("-P$BANNED_TARGETS_PROPERTY_NAME=linuxArm64")
                arguments.add(":klibApiCheckAll")
            }
        }

        runner.build().apply {
            assertTaskSuccess(":klibApiCheckAll")
        }
    }
}
