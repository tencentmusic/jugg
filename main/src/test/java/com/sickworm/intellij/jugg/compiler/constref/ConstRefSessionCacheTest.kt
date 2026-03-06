package com.sickworm.intellij.jugg.compiler.constref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConstRefSessionCacheTest {
    @Test
    fun `should throttle cleanup scan when cleanup interval not reached`() {
        val cache = ConstRefSessionCache(
            fileCacheMaxFiles = 100,
            lookupCacheMaxKeys = 100,
            ttlMs = 1L,
        )
        cache.putFileAnalysis(
            filePath = "A.kt",
            lastModified = 1L,
            checksum = 1L,
            definitions = listOf(createDefinition("A.kt", "MAX_A")),
            references = emptyList(),
        )
        Thread.sleep(20L)
        setLastCleanupMs(cache, System.currentTimeMillis())

        cache.putFileAnalysis(
            filePath = "B.kt",
            lastModified = 2L,
            checksum = 2L,
            definitions = listOf(createDefinition("B.kt", "MAX_B")),
            references = emptyList(),
        )

        assertEquals(2, readFileCacheSize(cache))
    }

    @Test
    fun `should still drop expired entry on direct lookup when cleanup is throttled`() {
        val cache = ConstRefSessionCache(
            fileCacheMaxFiles = 100,
            lookupCacheMaxKeys = 100,
            ttlMs = 1L,
        )
        cache.putFileAnalysis(
            filePath = "A.kt",
            lastModified = 1L,
            checksum = 1L,
            definitions = listOf(createDefinition("A.kt", "MAX_A")),
            references = emptyList(),
        )
        Thread.sleep(20L)
        setLastCleanupMs(cache, System.currentTimeMillis())

        assertNull(cache.getFileDefinitions("A.kt"))
        assertEquals(0, readFileCacheSize(cache))
    }

    private fun createDefinition(filePath: String, constName: String): ConstDefinition {
        return ConstDefinition(
            filePath = filePath,
            packageName = "com.example",
            fqClassName = "com.example.ConstantsKt",
            constName = constName,
            constType = "Int",
            constValue = "1",
        )
    }

    private fun setLastCleanupMs(cache: ConstRefSessionCache, value: Long) {
        val field = ConstRefSessionCache::class.java.getDeclaredField("lastCleanupMs")
        field.isAccessible = true
        field.setLong(cache, value)
    }

    private fun readFileCacheSize(cache: ConstRefSessionCache): Int {
        val field = ConstRefSessionCache::class.java.getDeclaredField("fileCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val fileCache = field.get(cache) as MutableMap<String, *>
        return fileCache.size
    }
}
