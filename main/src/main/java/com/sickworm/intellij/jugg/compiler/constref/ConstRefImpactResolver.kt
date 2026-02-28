package com.sickworm.intellij.jugg.compiler.constref

import java.io.File

/**
 * Resolves impacted source files from const-definition key deltas.
 */
internal class ConstRefImpactResolver(
    private val database: ConstRefCacheDatabase,
) {
    fun getEffectedFiles(
        changedPaths: List<String>,
        changedDefinitionKeys: Set<Pair<String, String>>,
        removedDefinitionKeys: Set<Pair<String, String>>,
    ): List<EffectedConstRef> {
        if (changedPaths.isEmpty()) {
            return emptyList()
        }
        database.registerPathHints(changedPaths)
        val changedSet = changedPaths.toSet()
        val byChangedDefinitions = database.getEffectedFilesByDefinitionKeys(changedDefinitionKeys, changedPaths)
        val byRemovedDefinitions = database.getEffectedFilesByDefinitionKeys(removedDefinitionKeys, changedPaths)
        return (byChangedDefinitions + byRemovedDefinitions)
            .filter { it.refFilePath !in changedSet && File(it.refFilePath).exists() }
            .distinctBy { "${it.refFilePath}|${it.defFqClassName}|${it.constName}" }
    }
}
