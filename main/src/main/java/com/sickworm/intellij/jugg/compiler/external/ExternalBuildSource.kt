package com.sickworm.intellij.jugg.compiler.external

import com.sickworm.intellij.jugg.project.info.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputDir
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.nio.file.Files

/** Toolchain cache directories that never hold user-editable external sources. */
private val externalBuildCacheDirectoryNames = setOf(".dart_tool", ".cxx", ".externalNativeBuild")

private val cppSourceExtensions = setOf(
    "c", "cc", "cpp", "cxx", "c++", "m", "mm", "s", "asm", "cppm", "ixx", "cu",
)

private val cppHeaderExtensions = setOf("h", "hh", "hpp", "hxx", "inc", "inl", "ipp", "tpp")

/** One module-specific external build matched by a changed physical source file. */
data class ExternalBuildTarget(
    val module: ModuleInfo,
    val buildInfo: ExternalBuildInfo,
    val matchedInputDir: ExternalBuildInputDir?,
)

/** Whether the file lives in a toolchain cache directory such as `.dart_tool`, `.cxx` or `.externalNativeBuild`. */
fun File.isInExternalBuildCacheDirectory(): Boolean {
    return toPath().toAbsolutePath().normalize().map { it.toString() }
        .any { it in externalBuildCacheDirectoryNames }
}

private fun File.isCppHeaderFile(): Boolean {
    if (name.startsWith(".")) return false
    return extension.lowercase() in cppHeaderExtensions
}

/** Whether one existing regular file is accepted below [root] by this rule. */
fun ExternalBuildInputFilterRule.matches(file: File, root: File): Boolean {
    if (!file.isFile) return false
    return when (this) {
        ExternalBuildInputFilterRule.Dart -> file.extension.equals("dart", ignoreCase = true)
        ExternalBuildInputFilterRule.FlutterAsset -> true
        ExternalBuildInputFilterRule.CppSource -> file.extension.lowercase() in cppSourceExtensions
        ExternalBuildInputFilterRule.CppHeader -> file.isCppHeaderFile()
        ExternalBuildInputFilterRule.NativeDirectory ->
            !hasHiddenSegment(file, root) && !hasSymbolicLink(file, root)
    }
}

private fun hasHiddenSegment(file: File, root: File): Boolean {
    val rootPath = root.absoluteFile.normalize().toPath()
    val filePath = file.absoluteFile.normalize().toPath()
    if (!filePath.startsWith(rootPath)) return false
    return rootPath.relativize(filePath).any { it.toString().startsWith(".") }
}

private fun hasSymbolicLink(file: File, root: File): Boolean {
    val rootPath = root.absoluteFile.normalize().path
    var current: File? = file.absoluteFile.normalize()
    while (current != null) {
        if (Files.isSymbolicLink(current.toPath())) return true
        if (current.path == rootPath) return false
        current = current.parentFile
    }
    return false
}

/**
 * Resolves the external build owning one changed file with the single rule set shared by change
 * detection, the incremental pre-check and the external build compiler, so they can never disagree.
 * Configuration inputs match exactly, while input directories deliberately match every descendant.
 * False positives are acceptable because Gradle remains the authority for task up-to-date checks.
 */
fun resolveExternalBuild(module: ModuleInfo, file: File): ExternalBuildInfo? {
    return resolveExternalBuilds(listOf(module), file).firstOrNull()?.buildInfo
}

/** Resolves every module-specific external build matched by one physical source file. */
fun resolveExternalBuilds(modules: Collection<ModuleInfo>, file: File): List<ExternalBuildTarget> {
    if (file.isInExternalBuildCacheDirectory()) {
        return emptyList()
    }
    if (!file.isFile) return emptyList()
    val path = file.toPath().toAbsolutePath().normalize()
    return modules.flatMap { module ->
        module.externalBuildInfos.mapNotNull { buildInfo ->
            if (buildInfo.excludedDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }) {
                return@mapNotNull null
            }
            if (buildInfo.configFiles.any { path == it.toPath().toAbsolutePath().normalize() }) {
                return@mapNotNull ExternalBuildTarget(module, buildInfo, null)
            }
            val matchedInputDir = buildInfo.inputDirs.filter { inputDir ->
                path.startsWith(inputDir.directory.toPath().toAbsolutePath().normalize()) &&
                        inputDir.filterRules.any { it.matches(file, inputDir.directory) }
            }.maxByOrNull { it.directory.toPath().nameCount }
            matchedInputDir?.let { ExternalBuildTarget(module, buildInfo, it) }
        }
    }
}
