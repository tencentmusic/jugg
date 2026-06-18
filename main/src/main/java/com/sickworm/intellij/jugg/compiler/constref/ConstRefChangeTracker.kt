package com.sickworm.intellij.jugg.compiler.constref

/**
 * Tracks const-definition deltas per source file so recompile impact can be narrowed to truly changed keys.
 */
internal class ConstRefChangeTracker {
    private val lock = Any()
    private val changedDefinitionChanges = mutableMapOf<String, Set<ConstDefinitionChange>>()
    private val removedDefinitionChanges = mutableMapOf<String, Set<ConstDefinitionChange>>()

    fun onFileDeleted(stdPath: String) {
        synchronized(lock) {
            changedDefinitionChanges.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            removedDefinitionChanges.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
        }
    }

    fun clearFile(filePath: String) {
        synchronized(lock) {
            changedDefinitionChanges.remove(filePath)
            removedDefinitionChanges.remove(filePath)
        }
    }

    fun updateDefinitionDiff(
        filePath: String,
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ) {
        val changedChanges = buildChangedDefinitionChanges(previousDefinitions, currentDefinitions)
        val removedChanges = changedChanges.filterTo(linkedSetOf()) { it.currentSignatures.isEmpty() }
        synchronized(lock) {
            if (changedChanges.isEmpty()) {
                changedDefinitionChanges.remove(filePath)
            } else {
                changedDefinitionChanges[filePath] = changedChanges
            }
            if (removedChanges.isEmpty()) {
                removedDefinitionChanges.remove(filePath)
            } else {
                removedDefinitionChanges[filePath] = removedChanges
            }
        }
    }

    fun collectChangedDefinitionKeys(changedPaths: Collection<String>): Set<Pair<String, String>> {
        return peekDefinitionDiff(changedPaths).first
    }

    fun collectRemovedDefinitionKeys(changedPaths: Collection<String>): Set<Pair<String, String>> {
        return peekDefinitionDiff(changedPaths).second
    }

    fun peekDefinitionDiff(changedPaths: Collection<String>): Pair<Set<Pair<String, String>>, Set<Pair<String, String>>> {
        val (changedChanges, removedChanges) = peekDefinitionChanges(changedPaths)
        return changedChanges.mapTo(linkedSetOf()) { it.key } to removedChanges.mapTo(linkedSetOf()) { it.key }
    }

    fun peekDefinitionChanges(
        changedPaths: Collection<String>,
    ): Pair<Set<ConstDefinitionChange>, Set<ConstDefinitionChange>> {
        synchronized(lock) {
            val changedChanges = changedPaths.flatMap { changedDefinitionChanges[it].orEmpty() }.toSet()
            val removedChanges = changedPaths.flatMap { removedDefinitionChanges[it].orEmpty() }.toSet()
            return changedChanges to removedChanges
        }
    }

    fun consumeDefinitionDiff(changedPaths: Collection<String>): Pair<Set<Pair<String, String>>, Set<Pair<String, String>>> {
        synchronized(lock) {
            val changedChanges = changedPaths.flatMap { changedDefinitionChanges.remove(it).orEmpty() }.toSet()
            val removedChanges = changedPaths.flatMap { removedDefinitionChanges.remove(it).orEmpty() }.toSet()
            return changedChanges.mapTo(linkedSetOf()) { it.key } to removedChanges.mapTo(linkedSetOf()) { it.key }
        }
    }

    private fun buildChangedDefinitionChanges(
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ): Set<ConstDefinitionChange> {
        if (previousDefinitions.isEmpty() && currentDefinitions.isEmpty()) {
            return emptySet()
        }
        val previousSignatureByKey = previousDefinitions
            .groupBy { it.fqClassName to it.constName }
            .mapValues { (_, defs) -> defs.mapTo(linkedSetOf()) { it.toSignature() } }
        val currentSignatureByKey = currentDefinitions
            .groupBy { it.fqClassName to it.constName }
            .mapValues { (_, defs) -> defs.mapTo(linkedSetOf()) { it.toSignature() } }
        return (previousSignatureByKey.keys + currentSignatureByKey.keys)
            .filter { key -> previousSignatureByKey[key] != currentSignatureByKey[key] }
            .mapTo(linkedSetOf()) { key ->
                ConstDefinitionChange(
                    fqClassName = key.first,
                    constName = key.second,
                    previousSignatures = previousSignatureByKey[key].orEmpty(),
                    currentSignatures = currentSignatureByKey[key].orEmpty(),
                )
            }
    }
}

/**
 * Structured const-definition delta used for diagnostics while key-based impact lookup stays unchanged.
 */
internal data class ConstDefinitionChange(
    val fqClassName: String,
    val constName: String,
    val previousSignatures: Set<ConstDefinitionSignature>,
    val currentSignatures: Set<ConstDefinitionSignature>,
) {
    val key: Pair<String, String>
        get() = fqClassName to constName

    fun toLogString(): String {
        return "$fqClassName.$constName: ${previousSignatures.toLogString()} -> ${currentSignatures.toLogString()}"
    }

    private fun Set<ConstDefinitionSignature>.toLogString(): String {
        return if (isEmpty()) {
            "<missing>"
        } else {
            joinToString(prefix = "[", postfix = "]") { it.toLogString() }
        }
    }
}

internal data class ConstDefinitionSignature(
    val constType: String,
    val constValue: String?,
) {
    fun toLogString(): String {
        return "$constType:${constValue ?: "<null>"}"
    }
}

private fun ConstDefinition.toSignature(): ConstDefinitionSignature {
    return ConstDefinitionSignature(constType = constType, constValue = constValue)
}
