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

/** Flutter resolution-aware asset directory names such as `2.0x`. */
private val flutterAssetVariantDirectoryName = Regex("""\d+(\.\d*)?x""")

/** Whether the file lives in a toolchain cache directory such as `.dart_tool`, `.cxx` or `.externalNativeBuild`. */
fun File.isInExternalBuildCacheDirectory(): Boolean {
    return toPath().toAbsolutePath().normalize().map { it.toString() }
        .any { it in externalBuildCacheDirectoryNames }
}

/**
 * Resolves the external build owning one changed file with the single rule set shared by change
 * detection, the incremental pre-check and the external build compiler, so they can never disagree.
 * Toolchain inputs and configuration inputs match first; broad source roots only accept source files
 * of the matching toolchain. Flutter directory inputs match direct assets and their resolution-aware
 * variants without expanding the match to arbitrary descendants.
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
        val isDeclaredInput = buildInfo.inputFiles.any { input ->
            path == input.toPath().toAbsolutePath().normalize() ||
                    buildInfo.matchesFlutterAssetInput(file, input)
        } ||
                buildInfo.configFiles.any { path == it.toPath().toAbsolutePath().normalize() }
        val isSourceUnderRoot = buildInfo.supportsSource(file) &&
                buildInfo.sourceDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }
        if (isDeclaredInput || isSourceUnderRoot) {
            return buildInfo
        }
    }
    return null
}

private fun ExternalBuildInfo.matchesFlutterAssetInput(file: File, input: File): Boolean {
    if (type != ExternalBuildType.Flutter) return false
    val normalizedInput = input.absoluteFile.normalize()
    if (!normalizedInput.isDirectory) return file.isFlutterAssetVariantOf(normalizedInput)
    if (file.parentFile?.absoluteFile?.normalize() == normalizedInput) return true
    val baseAsset = File(normalizedInput, file.name)
    return baseAsset.isFile && file.isFlutterAssetVariantOf(baseAsset)
}

private fun File.isFlutterAssetVariantOf(baseAsset: File): Boolean {
    if (name != baseAsset.name) return false
    val variantDirectory = parentFile ?: return false
    if (!flutterAssetVariantDirectoryName.matches(variantDirectory.name)) return false
    return variantDirectory.parentFile?.absoluteFile?.normalize() ==
            baseAsset.parentFile?.absoluteFile?.normalize()
}

/** Whether the file extension belongs to the sources of this external toolchain. */
fun ExternalBuildInfo.supportsSource(file: File): Boolean = when (type) {
    ExternalBuildType.Flutter -> file.extension.lowercase() == "dart"
    ExternalBuildType.Cpp -> file.extension.lowercase() in cppSourceExtensions
}
