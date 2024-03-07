/*
 * Copyright 2016-2024 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

package kotlinx.validation.api.klib

import kotlinx.validation.ExperimentalBCVApi
import org.jetbrains.kotlin.gradle.utils.`is`
import org.jetbrains.kotlin.library.abi.*
import java.io.File

/**
 * Filters affecting how the klib ABI will be represented in a dump.
 */
@ExperimentalBCVApi
public class KLibDumpFilters internal constructor(
    /**
     * Names of packages that should be excluded from a dump.
     * If a package is listed here, none of its declarations will be included in a dump.
     */
    public val ignoredPackages: Set<String>,
    /**
     * Names of classes that should be excluded from a dump.
     */
    public val ignoredClasses: Set<String>,
    /**
     * Names of annotations marking non-public declarations.
     * Such declarations will be excluded from a dump.
     */
    public val nonPublicMarkers: Set<String>,
    /**
     * KLib ABI signature version to include in a dump.
     */
    public val signatureVersion: KlibSignatureVersion
) {

    public class Builder @PublishedApi internal constructor() {
        /**
         * Names of packages that should be excluded from a dump.
         * If a package is listed here, none of its declarations will be included in a dump.
         *
         * By default, there are no ignored packages.
         */
        public val ignoredPackages: MutableSet<String> = mutableSetOf()

        /**
         * Names of classes that should be excluded from a dump.
         *
         * By default, there are no ignored classes.
         */
        public val ignoredClasses: MutableSet<String> = mutableSetOf()

        /**
         * Names of annotations marking non-public declarations.
         * Such declarations will be excluded from a dump.
         *
         * By default, a set of non-public markers is empty.
         */
        public val nonPublicMarkers: MutableSet<String> = mutableSetOf()

        /**
         * KLib ABI signature version to include in a dump.
         *
         * By default, the latest ABI signature version provided by a klib
         * and supported by a reader will be used.
         */
        public var signatureVersion: KlibSignatureVersion = KlibSignatureVersion.LATEST

        @PublishedApi
        internal fun build(): KLibDumpFilters {
            return KLibDumpFilters(ignoredPackages, ignoredClasses, nonPublicMarkers, signatureVersion)
        }
    }

    public companion object {
        /**
         * Default KLib ABI dump filters which declares no filters
         * and uses the latest KLib ABI signature version available.
         */
        public val DEFAULT: KLibDumpFilters = KLibDumpFilters {}
    }
}

/**
 * Builds a new [KLibDumpFilters] instance by invoking a [builderAction] on a temporary
 * [KLibDumpFilters.Builder] instance and then converting it into filters.
 *
 * Supplied [KLibDumpFilters.Builder] is valid only during the scope of [builderAction] execution.
 */
@ExperimentalBCVApi
public fun KLibDumpFilters(builderAction: KLibDumpFilters.Builder.() -> Unit): KLibDumpFilters {
    val builder = KLibDumpFilters.Builder()
    builderAction(builder)
    return builder.build()
}

@ExperimentalBCVApi
@OptIn(ExperimentalLibraryAbiReader::class)
internal fun dumpTo(to: Appendable, klibFile: File, filters: KLibDumpFilters) {
    require(klibFile.exists()) { "File does not exist: ${klibFile.absolutePath}" }
    val abiFilters = mutableListOf<AbiReadingFilter>()
    filters.ignoredClasses.toKlibNames().also {
        if (it.isNotEmpty()) {
            abiFilters.add(AbiReadingFilter.ExcludedClasses(it))
        }
    }
    filters.nonPublicMarkers.toKlibNames().also {
        if (it.isNotEmpty()) {
            abiFilters.add(AbiReadingFilter.NonPublicMarkerAnnotations(it))
        }
    }
    if (filters.ignoredPackages.isNotEmpty()) {
        abiFilters.add(AbiReadingFilter.ExcludedPackages(filters.ignoredPackages.map { AbiCompoundName(it) }))
    }

    val library = LibraryAbiReader.readAbiInfo(klibFile, abiFilters)

    val supportedSignatureVersions = library.signatureVersions.asSequence().filter { it.isSupportedByAbiReader }

    val signatureVersion = if (filters.signatureVersion == KlibSignatureVersion.LATEST) {
        supportedSignatureVersions.maxByOrNull { it.versionNumber }
            ?: throw IllegalStateException("Can't choose signatureVersion")
    } else {
        supportedSignatureVersions.find { it.versionNumber == filters.signatureVersion.version }
            ?: throw IllegalArgumentException(
                "Unsupported KLib signature version '${filters.signatureVersion.version}'. " +
                        "Supported versions are: ${
                            supportedSignatureVersions.map { it.versionNumber }.sorted().toList()
                        }"
            )
    }

    LibraryAbiRenderer.render(
        library, to, AbiRenderingSettings(
            renderedSignatureVersion = signatureVersion,
            renderManifest = true,
            renderDeclarations = true
        )
    )
}

// We're assuming that all names are in valid binary form as it's described in JVMLS §13.1:
// https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html#jls-13.1
@OptIn(ExperimentalLibraryAbiReader::class)
private fun Collection<String>.toKlibNames(): List<AbiQualifiedName> =
    this.map(String::toAbiQualifiedName).filterNotNull()

@OptIn(ExperimentalLibraryAbiReader::class)
internal fun String.toAbiQualifiedName(): AbiQualifiedName? {
    if (this.isBlank() || this.contains('/')) return null
    // Easiest part: dissect package name from the class name
    val idx = this.lastIndexOf('.')
    if (idx == -1) {
        return AbiQualifiedName(AbiCompoundName(""), this.classNameToCompoundName())
    } else {
        val packageName = this.substring(0, idx)
        val className = this.substring(idx + 1)
        return AbiQualifiedName(AbiCompoundName(packageName), className.classNameToCompoundName())
    }
}

@OptIn(ExperimentalLibraryAbiReader::class)
private fun String.classNameToCompoundName(): AbiCompoundName {
    if (this.isEmpty()) return AbiCompoundName(this)

    val segments = mutableListOf<String>()
    val builder = StringBuilder()

    for (idx in this.indices) {
        val c = this[idx]
        // Don't treat a character as a separator if:
        // - it's not a '$'
        // - it's at the beginning of the segment
        // - it's the last character of the string
        if (c != '$' || builder.isEmpty() || idx == this.length - 1) {
            builder.append(c)
            continue
        }
        check(c == '$')
        // class$$$susbclass -> class.$$subclass, were at second $ here.
        if (builder.last() == '$') {
            builder.append(c)
            continue
        }

        segments.add(builder.toString())
        builder.clear()
    }
    if (builder.isNotEmpty()) {
        segments.add(builder.toString())
    }
    return AbiCompoundName(segments.joinToString(separator = "."))
}
