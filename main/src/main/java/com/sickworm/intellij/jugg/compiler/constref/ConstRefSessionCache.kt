package com.sickworm.intellij.jugg.compiler.constref

/**
 * Session-level cache for const-ref analysis.
 * It keeps only hot entries in current IDE runtime and always allows DB fallback.
 */
internal class ConstRefSessionCache(
    fileCacheMaxFiles: Int,
    lookupCacheMaxKeys: Int,
    private val ttlMs: Long,
) {
    private val lock = Any()
    private var lastCleanupMs = 0L
    private val cleanupIntervalMs = DEFAULT_CLEANUP_INTERVAL_MS
    private val fileCache = object : LinkedHashMap<String, FileCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileCacheEntry>?): Boolean {
            return size > fileCacheMaxFiles
        }
    }
    private val lookupCache = object : LinkedHashMap<String, LookupCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LookupCacheEntry>?): Boolean {
            return size > lookupCacheMaxKeys
        }
    }

    fun getFileDefinitions(filePath: String): List<ConstDefinition>? {
        synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            cleanupExpiredLocked(nowMs)
            val entry = fileCache[filePath] ?: return null
            if (isExpired(entry.updatedAt, nowMs)) {
                fileCache.remove(filePath)
                return null
            }
            return entry.definitions
        }
    }

    fun putFileAnalysis(
        filePath: String,
        lastModified: Long,
        checksum: Long,
        definitions: List<ConstDefinition>,
        references: List<ConstReference>,
    ) {
        synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            cleanupExpiredLocked(nowMs)
            fileCache[filePath] = FileCacheEntry(
                lastModified = lastModified,
                checksum = checksum,
                definitions = definitions.toList(),
                references = references.toList(),
                updatedAt = nowMs,
            )
        }
    }

    fun putFileDefinitions(filePath: String, definitions: List<ConstDefinition>) {
        putFileAnalysis(
            filePath = filePath,
            lastModified = 0L,
            checksum = 0L,
            definitions = definitions,
            references = emptyList(),
        )
    }

    fun removeFile(filePath: String) {
        synchronized(lock) {
            fileCache.remove(filePath)
        }
    }

    fun removeFilesByPrefix(prefixPath: String) {
        synchronized(lock) {
            fileCache.keys.removeIf { it.startsWith(prefixPath) }
            lookupCache.clear()
        }
    }

    fun clearLookupCache() {
        synchronized(lock) {
            lookupCache.clear()
        }
    }

    fun getConstNameLookup(constName: String): List<ConstDefinition>? {
        @Suppress("UNCHECKED_CAST")
        return getLookupEntry(key = lookupKeyConstName(constName)) as? List<ConstDefinition>
    }

    fun putConstNameLookup(constName: String, definitions: List<ConstDefinition>) {
        putLookupEntry(
            key = lookupKeyConstName(constName),
            value = definitions.toList(),
        )
    }

    fun getClassConstLookup(fqClassName: String, constName: String): List<ConstDefinition>? {
        @Suppress("UNCHECKED_CAST")
        return getLookupEntry(key = lookupKeyClassConst(fqClassName, constName)) as? List<ConstDefinition>
    }

    fun putClassConstLookup(fqClassName: String, constName: String, definitions: List<ConstDefinition>) {
        putLookupEntry(
            key = lookupKeyClassConst(fqClassName, constName),
            value = definitions.toList(),
        )
    }

    fun getPackageConstLookup(packageName: String, constName: String): List<ConstDefinition>? {
        @Suppress("UNCHECKED_CAST")
        return getLookupEntry(key = lookupKeyPackageConst(packageName, constName)) as? List<ConstDefinition>
    }

    fun putPackageConstLookup(packageName: String, constName: String, definitions: List<ConstDefinition>) {
        putLookupEntry(
            key = lookupKeyPackageConst(packageName, constName),
            value = definitions.toList(),
        )
    }

    fun getSimpleClassLookup(simpleName: String): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return getLookupEntry(key = lookupKeySimpleClass(simpleName)) as? Set<String>
    }

    fun putSimpleClassLookup(simpleName: String, fqClasses: Set<String>) {
        putLookupEntry(
            key = lookupKeySimpleClass(simpleName),
            value = fqClasses.toSet(),
        )
    }

    private fun getLookupEntry(key: String): Any? {
        synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            cleanupExpiredLocked(nowMs)
            val entry = lookupCache[key] ?: return null
            if (isExpired(entry.updatedAt, nowMs)) {
                lookupCache.remove(key)
                return null
            }
            return entry.value
        }
    }

    private fun putLookupEntry(key: String, value: Any) {
        synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            cleanupExpiredLocked(nowMs)
            lookupCache[key] = LookupCacheEntry(
                value = value,
                updatedAt = nowMs,
            )
        }
    }

    private fun cleanupExpiredLocked(nowMs: Long) {
        if (ttlMs <= 0L) {
            return
        }
        if (nowMs - lastCleanupMs < cleanupIntervalMs) {
            return
        }
        lastCleanupMs = nowMs
        fileCache.entries.removeIf { (_, entry) -> isExpired(entry.updatedAt, nowMs) }
        lookupCache.entries.removeIf { (_, entry) -> isExpired(entry.updatedAt, nowMs) }
    }

    private fun isExpired(updatedAt: Long, nowMs: Long): Boolean {
        return ttlMs > 0L && nowMs - updatedAt > ttlMs
    }

    private fun lookupKeyConstName(constName: String): String {
        return "const:$constName"
    }

    private fun lookupKeyClassConst(fqClassName: String, constName: String): String {
        return "class:$fqClassName|$constName"
    }

    private fun lookupKeyPackageConst(packageName: String, constName: String): String {
        return "package:$packageName|$constName"
    }

    private fun lookupKeySimpleClass(simpleName: String): String {
        return "simple:$simpleName"
    }

    private data class FileCacheEntry(
        val lastModified: Long,
        val checksum: Long,
        val definitions: List<ConstDefinition>,
        val references: List<ConstReference>,
        val updatedAt: Long,
    )

    private data class LookupCacheEntry(
        val value: Any,
        val updatedAt: Long,
    )

    companion object {
        private const val DEFAULT_CLEANUP_INTERVAL_MS = 60_000L
    }
}
