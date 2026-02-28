package com.sickworm.intellij.jugg.compiler.constref

/**
 * Tracks const-definition deltas per source file so recompile impact can be narrowed to truly changed keys.
 */
internal class ConstRefChangeTracker {
    private val lock = Any()
    private val changedDefinitionKeys = mutableMapOf<String, Set<Pair<String, String>>>()
    private val removedDefinitionKeys = mutableMapOf<String, Set<Pair<String, String>>>()

    fun onFileDeleted(stdPath: String) {
        synchronized(lock) {
            changedDefinitionKeys.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            removedDefinitionKeys.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
        }
    }

    fun clearFile(filePath: String) {
        synchronized(lock) {
            changedDefinitionKeys.remove(filePath)
            removedDefinitionKeys.remove(filePath)
        }
    }

    fun updateDefinitionDiff(
        filePath: String,
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ) {
        val changedKeys = buildChangedDefinitionKeys(previousDefinitions, currentDefinitions)
        val removedKeys = buildRemovedDefinitionKeys(previousDefinitions, currentDefinitions)
        synchronized(lock) {
            if (changedKeys.isEmpty()) {
                changedDefinitionKeys.remove(filePath)
            } else {
                changedDefinitionKeys[filePath] = changedKeys
            }
            if (removedKeys.isEmpty()) {
                removedDefinitionKeys.remove(filePath)
            } else {
                removedDefinitionKeys[filePath] = removedKeys
            }
        }
    }

    fun collectChangedDefinitionKeys(changedPaths: Collection<String>): Set<Pair<String, String>> {
        synchronized(lock) {
            return changedPaths.flatMap { changedDefinitionKeys[it].orEmpty() }.toSet()
        }
    }

    fun collectRemovedDefinitionKeys(changedPaths: Collection<String>): Set<Pair<String, String>> {
        synchronized(lock) {
            return changedPaths.flatMap { removedDefinitionKeys[it].orEmpty() }.toSet()
        }
    }

    private fun buildRemovedDefinitionKeys(
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ): Set<Pair<String, String>> {
        if (previousDefinitions.isEmpty()) {
            return emptySet()
        }
        val oldKeys = previousDefinitions.map { it.fqClassName to it.constName }.toSet()
        val currentKeys = currentDefinitions.map { it.fqClassName to it.constName }.toSet()
        return oldKeys - currentKeys
    }

    private fun buildChangedDefinitionKeys(
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ): Set<Pair<String, String>> {
        if (previousDefinitions.isEmpty() && currentDefinitions.isEmpty()) {
            return emptySet()
        }
        val previousSignatureByKey = previousDefinitions
            .groupBy { it.fqClassName to it.constName }
            .mapValues { (_, defs) -> defs.map { "${it.constType}:${it.constValue.orEmpty()}" }.toSet() }
        val currentSignatureByKey = currentDefinitions
            .groupBy { it.fqClassName to it.constName }
            .mapValues { (_, defs) -> defs.map { "${it.constType}:${it.constValue.orEmpty()}" }.toSet() }
        return (previousSignatureByKey.keys + currentSignatureByKey.keys)
            .filterTo(linkedSetOf()) { key -> previousSignatureByKey[key] != currentSignatureByKey[key] }
    }
}
