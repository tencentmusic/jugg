package com.sickworm.intellij.jugg.deploy.cache

import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap

/**
 * Persists the deployment snapshot for one project under its build/jugg directory.
 * All reads and writes use the shared project lock, and writes replace the database atomically.
 */
class JuggDeploymentCacheStore(
    private val cacheDbFile: File,
    private val taskRunnerManager: TaskRunnerManager,
    private val maxSize: Int = DEFAULT_SIZE,
) {
    private val entries = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    init {
        taskRunnerManager.runProjectWriteLocked("Load deployment cache") {
            loadFromFile()
        }
    }

    fun preInit() {
        taskRunnerManager.runProjectWriteLocked("Preload deployment cache") {
            entries.size
        }
    }

    fun store(deviceSerial: String, packageName: String, entry: CacheEntry) {
        taskRunnerManager.runProjectWriteLocked("Store deployment cache") {
            loadFromFile()
            entries[key(deviceSerial, packageName)] = entry
            writeToFile()
        }
    }

    fun load(deviceSerial: String, packageName: String): CacheEntry? {
        return taskRunnerManager.runProjectWriteLocked("Load deployment cache entry") {
            loadFromFile()
            entries[key(deviceSerial, packageName)]
        }
    }

    private fun writeToFile() {
        cacheDbFile.parentFile?.mkdirs()
        val tempFile = File(cacheDbFile.parentFile, "${cacheDbFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fileOutput ->
                ObjectOutputStream(fileOutput).use { output ->
                    output.writeObject(LinkedHashMap(entries))
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            moveAtomically(tempFile, cacheDbFile)
        } catch (_: IOException) {
            tempFile.delete()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromFile() {
        entries.clear()
        try {
            ObjectInputStream(FileInputStream(cacheDbFile)).use { input ->
                entries.putAll(input.readObject() as LinkedHashMap<String, CacheEntry>)
            }
        } catch (_: FileNotFoundException) {
            // No cache has been written yet.
        } catch (_: IOException) {
            // Cache read failure should degrade to a cache miss.
        } catch (_: ClassNotFoundException) {
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

        fun key(deviceSerial: String, packageName: String): String = "$deviceSerial:$packageName"
    }
}
