package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.project.data.ExternalBuildInfoUpdate
import com.sickworm.intellij.jugg.project.data.ModuleInfo

/** Persists task-local external build metadata and returns the effective module snapshot. */
interface IExternalBuildInfoUpdater {
    fun update(updates: List<ExternalBuildInfoUpdate>): Map<String, ModuleInfo>?
}

/** Replaces only the requested external build records and preserves every unrelated module field. */
fun mergeExternalBuildInfoUpdates(
    modules: Map<String, ModuleInfo>,
    updates: List<ExternalBuildInfoUpdate>,
): Map<String, ModuleInfo>? {
    var result = modules
    updates.forEach { update ->
        val moduleEntry = result.entries.singleOrNull {
            val module = it.value
            module.name == update.moduleName &&
                    module.moduleRootDir.absoluteFile.normalize() == update.moduleRootDir.absoluteFile.normalize() &&
                    module.buildVariant == update.buildVariant
        } ?: return null
        val module = moduleEntry.value
        val index = module.externalBuildInfos.indexOfFirst {
            it.type == update.externalBuildInfo.type && it.taskPath == update.previousTaskPath
        }
        if (index < 0) {
            return null
        }
        val externalBuildInfos = module.externalBuildInfos.toMutableList()
        externalBuildInfos[index] = update.externalBuildInfo
        result = result + (moduleEntry.key to module.copy(externalBuildInfos = externalBuildInfos))
    }
    return result
}
