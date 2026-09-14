package com.sickworm.intellij.jugg.compiler.external

import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

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
 * Configuration inputs match exactly, while input directories deliberately match every descendant.
 * False positives are acceptable because Gradle remains the authority for task up-to-date checks.
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
        val isConfigInput = buildInfo.configFiles.any { path == it.toPath().toAbsolutePath().normalize() }
        val isUnderInputDir = buildInfo.inputDirs.any {
            path.startsWith(it.toPath().toAbsolutePath().normalize())
        }
        if (isConfigInput || isUnderInputDir) {
            return buildInfo
        }
    }
    return null
}
