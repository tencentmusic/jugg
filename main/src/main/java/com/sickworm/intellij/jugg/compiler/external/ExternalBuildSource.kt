package com.sickworm.intellij.jugg.compiler.external

import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputDir
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule
import com.sickworm.intellij.jugg.project.data.ExternalBuildPrerequisite
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files

/** Toolchain cache directories that never hold user-editable external sources. */
private val externalBuildCacheDirectoryNames = setOf(".dart_tool", ".cxx", ".externalNativeBuild")

/** C/C++ source extensions accepted by an external native configuration root. */
private val cppSourceExtensions = setOf(
    "c", "cc", "cpp", "cxx", "c++", "m", "mm", "s", "asm", "cppm", "ixx", "cu",
)

/** C/C++ header extensions accepted by a configuration root, include root or metadata source. */
private val cppHeaderExtensions = setOf("h", "hh", "hpp", "hxx", "inc", "inl", "ipp", "tpp")

/** One module-specific external build matched by a changed physical source file. */
data class ExternalBuildTarget(
    val module: ModuleInfo,
    val buildInfo: ExternalBuildInfo,
    /** Input directory that actually accepted the file; null when a configuration file matched. */
    val matchedInputDir: ExternalBuildInputDir?,
)

/** Whether the file lives in a toolchain cache directory such as `.dart_tool`, `.cxx` or `.externalNativeBuild`. */
fun File.isInExternalBuildCacheDirectory(): Boolean {
    return toPath().toAbsolutePath().normalize().map { it.toString() }
        .any { it in externalBuildCacheDirectoryNames }
}

/** Whether the C/C++ file is a header, which keeps its directory on header-only matching. */
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

/** Whether any path segment below [root], including the file name, is hidden. */
private fun hasHiddenSegment(file: File, root: File): Boolean {
    val rootPath = root.absoluteFile.normalize().toPath()
    val filePath = file.absoluteFile.normalize().toPath()
    if (!filePath.startsWith(rootPath)) return false
    return rootPath.relativize(filePath).any { it.toString().startsWith(".") }
}

/**
 * Whether the file itself or a directory between it and [root] is a symbolic link. Metadata source
 * directories may contain links to unrelated trees, and their targets are not build inputs.
 */
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
 * Resolves the first external build matched by one module for legacy single-module callers.
 */
fun resolveExternalBuild(module: ModuleInfo, file: File): ExternalBuildInfo? {
    return resolveExternalBuilds(listOf(module), file).firstOrNull()?.buildInfo
}

/**
 * Resolves every module-specific external build matched by one physical source file. Configuration
 * files match exactly, while input directories match the files their filter rules accept. Only
 * current regular files are inputs: a deleted path has no type to restore, and a directory event is
 * expanded by the caller instead. One file produces at most one target per external build even when
 * several input directories of that build accept it.
 */
fun resolveExternalBuilds(modules: Collection<ModuleInfo>, file: File): List<ExternalBuildTarget> {
    if (file.isInExternalBuildCacheDirectory()) {
        return emptyList()
    }
    if (!file.isFile) {
        return emptyList()
    }
    val path = file.toPath().toAbsolutePath().normalize()
    return modules.flatMap { module ->
        module.externalBuildInfos.mapNotNull { buildInfo ->
            if (buildInfo.excludedDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }) {
                return@mapNotNull null
            }
            if (buildInfo.configFiles.any { path == it.toPath().toAbsolutePath().normalize() }) {
                return@mapNotNull ExternalBuildTarget(module, buildInfo, null)
            }
            val matchedInputDir = buildInfo.inputDirs
                .filter { inputDir ->
                    path.startsWith(inputDir.directory.toPath().toAbsolutePath().normalize()) &&
                            inputDir.filterRules.any { it.matches(file, inputDir.directory) }
                }
                .maxByOrNull { it.directory.toPath().nameCount }
            matchedInputDir?.let { ExternalBuildTarget(module, buildInfo, it) }
        }
    }
}

/**
 * Prerequisites whose trigger globs accept at least one of [files]. Matching is path-based: a
 * deleted path can still fire a glob so the caller can fall back instead of ignoring the event.
 */
fun matchedPrerequisites(
    buildInfo: ExternalBuildInfo,
    files: Collection<File>,
): List<ExternalBuildPrerequisite> {
    return buildInfo.prerequisites.filter { prerequisite ->
        files.any { file -> matchesPrerequisite(file, buildInfo, prerequisite) }
    }
}

/**
 * Resolves a deleted path that still matches a declared prerequisite glob. Ordinary deleted
 * external sources stay untyped; only codegen triggers need a ChangedFile so the compile precheck
 * can fall back to a full Gradle build.
 */
fun resolveDeletedPrerequisiteTrigger(
    modules: Collection<ModuleInfo>,
    file: File,
): ExternalBuildTarget? {
    if (file.isDirectory || file.isInExternalBuildCacheDirectory()) {
        return null
    }
    modules.forEach { module ->
        module.externalBuildInfos.forEach { buildInfo ->
            val matched = matchedPrerequisites(buildInfo, listOf(file))
            if (matched.isEmpty()) {
                return@forEach
            }
            val matchedInputDir = deepestContainingInputDir(file, buildInfo) ?: return@forEach
            return ExternalBuildTarget(module, buildInfo, matchedInputDir)
        }
    }
    return null
}

/** Whether [file] is under an input root of [buildInfo] and matches one glob of [prerequisite]. */
fun matchesPrerequisite(
    file: File,
    buildInfo: ExternalBuildInfo,
    prerequisite: ExternalBuildPrerequisite,
): Boolean {
    val path = file.toPath().toAbsolutePath().normalize()
    if (buildInfo.excludedDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }) {
        return false
    }
    return prerequisite.triggerGlobs.any { glob ->
        buildInfo.inputDirs.any { inputDir ->
            matchesExternalBuildTriggerGlob(file, glob, inputDir.directory)
        }
    }
}

/**
 * Matches [glob] against [file] relative to [root]. The file does not have to exist, so a deleted
 * trigger path can still be classified. Invalid globs never match.
 */
fun matchesExternalBuildTriggerGlob(file: File, glob: String, root: File): Boolean {
    if (glob.isEmpty()) {
        return false
    }
    val rootPath = root.toPath().toAbsolutePath().normalize()
    val filePath = file.toPath().toAbsolutePath().normalize()
    if (!filePath.startsWith(rootPath)) {
        return false
    }
    val relative = rootPath.relativize(filePath).toString().replace('\\', '/')
    if (relative.isEmpty()) {
        return false
    }
    val matcher = runCatching {
        FileSystems.getDefault().getPathMatcher("glob:$glob")
    }.getOrNull() ?: return false
    return matcher.matches(FileSystems.getDefault().getPath(relative))
}

private fun deepestContainingInputDir(file: File, buildInfo: ExternalBuildInfo): ExternalBuildInputDir? {
    val path = file.toPath().toAbsolutePath().normalize()
    return buildInfo.inputDirs
        .filter { path.startsWith(it.directory.toPath().toAbsolutePath().normalize()) }
        .maxByOrNull { it.directory.toPath().nameCount }
}
