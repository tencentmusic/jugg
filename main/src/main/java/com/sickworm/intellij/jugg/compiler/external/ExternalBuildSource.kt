package com.sickworm.intellij.jugg.compiler.external

import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** C/C++ source, header, template and assembly extensions handled by native external builds. */
val cppSourceExtensions = setOf(
    "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inc", "inl", "ipp", "tpp", "s", "asm",
)

/** Toolchain cache directories that never hold user-editable external sources. */
private val externalBuildCacheDirectoryNames = setOf(".dart_tool", ".cxx", ".externalNativeBuild")

/** Whether the file lives in a toolchain cache directory such as `.dart_tool`, `.cxx` or `.externalNativeBuild`. */
fun File.isInExternalBuildCacheDirectory(): Boolean {
    return toPath().toAbsolutePath().normalize().map { it.toString() }
        .any { it in externalBuildCacheDirectoryNames }
}

/**
 * Resolves the external build owning one changed file with the single rule set shared by change
 * detection, the incremental pre-check and the external build compiler, so they can never disagree.
 * Exact toolchain inputs and configuration inputs match first; broad source roots only accept source
 * files of the matching toolchain.
 */
fun resolveExternalBuild(module: ModuleInfo, file: File): ExternalBuildInfo? {
    if (file.isInExternalBuildCacheDirectory()) {
        return null
    }
    val path = file.toPath().toAbsolutePath().normalize()
    module.externalBuildInfos.forEach buildLoop@{ buildInfo ->
        if (buildInfo.excludedDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }) {
            return@buildLoop
        }
        val isExactInput = buildInfo.inputFiles.any { path == it.toPath().toAbsolutePath().normalize() } ||
                buildInfo.configFiles.any { path == it.toPath().toAbsolutePath().normalize() }
        val isSourceUnderRoot = buildInfo.supportsSource(file) &&
                buildInfo.sourceDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }
        if (isExactInput || isSourceUnderRoot) {
            return buildInfo
        }
    }
    return null
}

/** Whether the file extension belongs to the sources of this external toolchain. */
fun ExternalBuildInfo.supportsSource(file: File): Boolean = when (type) {
    ExternalBuildType.Flutter -> file.extension.lowercase() == "dart"
    ExternalBuildType.Cpp -> file.extension.lowercase() in cppSourceExtensions
}
