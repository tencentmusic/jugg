package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.compile.crc32
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe, lazily loaded, persisted CRC32 cache keyed by normalized file path.
 * Preserved across deploy history resets so repeated full compiles and cold recoveries skip unchanged files.
 */
internal class FileCrcCache(
    private val cacheFile: File,
    private val logger: Logger,
) {

    private val entriesDelegate = lazy { loadEntries() }
    private val entries: ConcurrentHashMap<String, Entry> by entriesDelegate
    private val dirty = AtomicBoolean(false)
    private val ioLock = Any()

    val isDirty: Boolean
        get() = dirty.get()

    fun getCrc32(file: File): Long {
        val normalizedFile = file.absoluteFile.normalize()
        val attributes = Files.readAttributes(
            normalizedFile.toPath(),
            BasicFileAttributes::class.java,
        )
        val lastModifiedTimeMillis = attributes.lastModifiedTime().toMillis()
        val length = attributes.size()
        val cached = entries[normalizedFile.path]
        if (cached != null &&
            cached.lastModifiedTimeMillis == lastModifiedTimeMillis &&
            cached.length == length
        ) {
            return cached.crc32
        }

        val crc = normalizedFile.crc32
        entries[normalizedFile.path] = Entry(lastModifiedTimeMillis, length, crc)
        dirty.set(true)
        return crc
    }

    fun pruneAndSave(activePaths: Set<String>) {
        synchronized(ioLock) {
            if (activePaths.isEmpty()) {
                if (entriesDelegate.isInitialized()) {
                    entries.clear()
                }
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                dirty.set(false)
                return
            }
            val removed = entries.keys.removeIf { it !in activePaths }
            if (removed || dirty.get() || (entries.isEmpty() && cacheFile.exists())) {
                saveLocked()
            }
        }
    }

    private fun loadEntries(): ConcurrentHashMap<String, Entry> {
        val map = ConcurrentHashMap<String, Entry>()
        if (!cacheFile.exists()) {
            return map
        }
        try {
            val persisted = Gson().fromJson(cacheFile.readText(Charsets.UTF_8), PersistedCache::class.java)
            if (persisted == null || persisted.version != VERSION) {
                return map
            }
            persisted.entries?.forEach { (path, entry) ->
                if (path.isNotEmpty() && entry != null) {
                    map[path] = entry
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to load CRC cache from ${cacheFile.absolutePath}, will recompute", e)
        }
        return map
    }

    private fun saveLocked() {
        val snapshot = HashMap(entries)
        try {
            if (snapshot.isEmpty()) {
                cacheFile.delete()
                dirty.set(false)
                return
            }
            cacheFile.parentFile?.mkdirs()
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tempFile.writeText(Gson().toJson(PersistedCache(entries = snapshot)), Charsets.UTF_8)
            Files.move(
                tempFile.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            dirty.set(false)
        } catch (e: Exception) {
            logger.debug("Failed to save CRC cache to ${cacheFile.absolutePath}", e)
        }
    }

    private data class Entry(
        val lastModifiedTimeMillis: Long,
        val length: Long,
        val crc32: Long,
    )

    private data class PersistedCache(
        val version: Int = VERSION,
        val entries: Map<String, Entry?>? = null,
    )

    companion object {
        private const val VERSION = 1
    }
}
