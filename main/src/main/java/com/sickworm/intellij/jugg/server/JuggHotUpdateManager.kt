package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.JarFileInfo
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Downloads, validates, publishes, and cleans shared Jugg hot-update files. */
class JuggHotUpdateManager(
    private val juggServer: JuggServer,
    private val taskRunnerManager: TaskRunnerManager,
    private val loadBaseBuildTime: String,
    val hotUpdateDir: File,
    logger: Logger,
) {
    val storageDir: File = File(hotUpdateDir, "jars")
    val loadManifestFile: File = File(hotUpdateDir, "load_manifest.json")
    val hotUpdateDataFile: File = File(hotUpdateDir, "hot_update_data.json")
    private val logger = logger.getInstance("JuggHotUpdateManager")

    fun prepareUpdate(data: HotUpdateData): JuggHotUpdateResult {
        return taskRunnerManager.runGlobalWriteLocked("Prepare hot update") {
            require(data.isNeedUpdate) { "Hot update data does not require an update" }
            if (!data.isNeedReinstall) require(loadBaseBuildTime.isNotBlank()) { "Runtime build time is required for compatible hot update" }
            logger.debug("Prepare hot update start: targetVersion=${data.targetVersion}, isNeedReinstall=${data.isNeedReinstall}, storageDir=$storageDir")
            storageDir.mkdirs()
            val previousVersion = readHotUpdateData()?.targetVersion
            val jarFiles = data.jarFileInfos.map(::prepareJar)
            val missing = jarFiles.filterNot(File::isFile)
            check(missing.isEmpty()) { "Hot update jars are missing: ${missing.map(File::getName)}" }
            logger.debug("Write hot update metadata: $hotUpdateDataFile")
            writeJsonAtomically(hotUpdateDataFile, data)
            if (data.isNeedReinstall) {
                logger.debug("Skip load manifest for reinstall hot update")
            } else {
                logger.debug("Publish hot update load manifest: $loadManifestFile")
                publishLoadManifest(HotUpdateLoadManifest(loadBaseBuildTime, jarFiles.map(File::getName)))
            }
            val referencedJarNames = resolveLoadManifest(loadBaseBuildTime)?.jarFileNames.orEmpty().toMutableSet()
            referencedJarNames.addAll(jarFiles.map(File::getName))
            cleanupExpiredJarsLocked(referencedJarNames)
                .forEach { logger.debug("Delete expired hot update jar: ${it.absolutePath}") }
            logger.debug("Prepare hot update success: targetVersion=${data.targetVersion}")
            JuggHotUpdateResult(previousVersion, jarFiles)
        }
    }

    private fun prepareJar(jar: JarFileInfo): File {
        val target = storageDir.resolve(jar.uniqueName)
        if (target.isFile) {
            val actualMd5 = target.md5()
            if (actualMd5 == jar.md5) {
                logger.debug("Reuse verified hot update jar: ${jar.uniqueName}")
                return target
            }
            logger.debug("Existing hot update jar MD5 mismatch: ${jar.uniqueName}, expect=${jar.md5}, actual=$actualMd5")
        }
        val temp = File(storageDir, "${jar.uniqueName}.${UUID.randomUUID()}.tmp")
        logger.debug("Download hot update jar start: ${jar.uniqueName}, url=${jar.url}")
        try {
            juggServer.downloadFile(jar.url, temp)
            check(temp.isFile && temp.length() > 0L) { "Downloaded hot update jar is empty: ${jar.uniqueName}" }
            val actualMd5 = temp.md5()
            check(actualMd5 == jar.md5) { "MD5 check failed for ${jar.uniqueName}, expect=${jar.md5}, actual=$actualMd5" }
            replaceFile(temp, target)
            logger.debug("Download hot update jar finished: ${jar.uniqueName}")
            return target
        } catch (e: Exception) {
            logger.debug("Download hot update jar failed: ${jar.uniqueName}", e)
            throw e
        } finally {
            temp.delete()
        }
    }

    fun publishEmbeddedIfNeeded(embeddedLibDir: File): Boolean {
        if (!hotUpdateDir.exists() || loadBaseBuildTime.isEmpty() || !embeddedLibDir.isDirectory) return false
        return taskRunnerManager.runGlobalWriteLocked("Publish embedded hot update") {
            if (resolveLoadManifest(loadBaseBuildTime) != null) return@runGlobalWriteLocked false
            val embeddedJars = embeddedLibDir.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" }.sortedBy { it.name }
            if (embeddedJars.isEmpty()) return@runGlobalWriteLocked false
            storageDir.mkdirs()
            embeddedJars.forEach { source ->
                val target = storageDir.resolve(source.name)
                val temp = File(storageDir, "${source.name}.${UUID.randomUUID()}.tmp")
                try {
                    source.copyTo(temp, overwrite = true)
                    replaceFile(temp, target)
                } finally {
                    temp.delete()
                }
            }
            publishLoadManifest(HotUpdateLoadManifest(loadBaseBuildTime, embeddedJars.map { it.name }))
            true
        }
    }

    fun cleanupExpiredJars(referencedJarNames: Set<String>, nowMillis: Long = System.currentTimeMillis()): List<File> {
        return taskRunnerManager.runGlobalWriteLocked("Cleanup hot update jars") {
            cleanupExpiredJarsLocked(referencedJarNames, nowMillis)
        }
    }

    internal fun resolveLoadManifest(embeddedBuildTime: String): HotUpdateLoadManifest? {
        if (embeddedBuildTime.isEmpty() || !loadManifestFile.isFile) return null
        return runCatching { Gson().fromJson(loadManifestFile.readText(), HotUpdateLoadManifest::class.java) }.getOrNull()
            ?.takeIf { it.baseEmbeddedBuildTime == embeddedBuildTime && it.jarFileNames.isNotEmpty() }
    }

    internal fun publishLoadManifest(manifest: HotUpdateLoadManifest) {
        writeJsonAtomically(loadManifestFile, manifest)
    }

    private fun readHotUpdateData(): HotUpdateData? {
        if (!hotUpdateDataFile.isFile) return null
        return try {
            Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
        } catch (e: Exception) {
            logger.debug("Read current hot update data failed", e)
            null
        }
    }

    private fun cleanupExpiredJarsLocked(referencedJarNames: Set<String>, nowMillis: Long = System.currentTimeMillis()): List<File> {
        val expiredBeforeMillis = nowMillis - JAR_RETENTION_MILLIS
        return storageDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .filter { it.name !in referencedJarNames && it.lastModified() < expiredBeforeMillis }
            .filter { it.delete() }
    }

    private fun writeJsonAtomically(target: File, value: Any) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            tempFile.writeText(Gson().toJson(value))
            replaceFile(tempFile, target)
        } finally {
            tempFile.delete()
        }
    }

    private fun replaceFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun File.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val JAR_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}

/** Describes verified hot-update jars prepared for host-specific installation or restart handling. */
data class JuggHotUpdateResult(
    val previousVersion: String?,
    val jarFiles: List<File>,
)
