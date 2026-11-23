package com.sickworm.intellij.jugg.cmdline

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import java.io.File

/** Loads the primary and included-build snapshots, preserving backed-up primary modules. */
internal fun loadCiProjectInfo(pathManager: JuggPathManager, logger: Logger, skipVersionCheck: Boolean): JuggProjectInfo {
    val primary = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger)
        .loadPreservingFile(isSkipVersionCheck = skipVersionCheck)
        ?: error("Gradle project info is unavailable: ${pathManager.gradleProjectInfoFile}")
    require(primary.modules.isNotEmpty()) { "Gradle project info has no modules" }
    val modules = primary.modules.toMutableMap()
    val included = pathManager.gradleIncludeBuildsFile
    if (!included.exists()) return primary
    included.readLines().filter { it.isNotBlank() }.forEach { path ->
        // The manifest contains build-machine paths; archives retain snapshots next to the manifest.
        val snapshot = File(included.parentFile, File(path).name)
        val info = ProjectInfoSerializer(snapshot, logger).loadPreservingFile(isSkipVersionCheck = skipVersionCheck)
            ?: error("Included-build project info is unavailable: $snapshot")
        info.modules.forEach { (name, module) ->
            val previous = modules[name]
            require(previous == null || previous.moduleRootDir == module.moduleRootDir) {
                "Conflicting CI module $name: ${previous?.moduleRootDir} and ${module.moduleRootDir}"
            }
            modules.putIfAbsent(name, module)
        }
    }
    return primary.copy(modules = modules)
}
