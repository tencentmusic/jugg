package com.sickworm.intellij.jugg.deploy.run

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.LinkedHashMap

/**
 * Local source implementation of the deployment cache database.
 */
internal class JuggDeploymentCacheStore(
    private val cacheDbFile: File,
    private val maxSize: Int = DEFAULT_SIZE,
) {
    private val entries = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    init {
        loadFromFile()
    }

    fun preInit() {
        entries.size
    }

    fun store(deviceSerial: String, packageName: String, entry: CacheEntry) {
        entries[key(deviceSerial, packageName)] = entry
        writeToFile()
    }

    fun load(deviceSerial: String, packageName: String): CacheEntry? {
        return entries[key(deviceSerial, packageName)]
    }

    private fun writeToFile() {
        cacheDbFile.parentFile?.mkdirs()
        if (cacheDbFile.exists()) {
            cacheDbFile.delete()
        }
        try {
            ObjectOutputStream(FileOutputStream(cacheDbFile)).use { outputStream ->
                outputStream.writeObject(LinkedHashMap(entries))
                outputStream.flush()
            }
        } catch (ignored: IOException) {
            // Cache write failure should degrade to a cache miss next time.
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromFile() {
        try {
            ObjectInputStream(FileInputStream(cacheDbFile)).use { inputStream ->
                val storedEntries = inputStream.readObject() as LinkedHashMap<String, CacheEntry>
                entries.putAll(storedEntries)
            }
        } catch (ignored: FileNotFoundException) {
            // No cache has been written yet.
        } catch (ignored: IOException) {
            // Cache read failure should degrade to a cache miss.
        } catch (ignored: ClassNotFoundException) {
            // Cache read failure should degrade to a cache miss.
        }
    }

    data class CacheEntry(
        val apkPaths: List<String>,
        val overlayId: OverlayId,
    ) : Serializable

    data class OverlayId(
        val sha: String,
        val isBaseInstall: Boolean,
        val overlayFiles: List<OverlayFile>,
    ) : Serializable

    data class OverlayFile(
        val path: String,
        val checksum: Long,
    ) : Serializable

    private companion object {
        const val DEFAULT_SIZE = 4

        fun key(deviceSerial: String, packageName: String): String {
            return String.format("%s:%s", deviceSerial, packageName)
        }
    }
}
