package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.info.SigningConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


/**
 * ResourceApkModifier creates and updates a resource-only APK payload for overlay deployment.
 * Collaboration: Converts [DeployItem] overlays into ZIP content and delegates incremental patching to [ApkFileModifier.addFile] and [ApkFileModifier.updateDirectly].
 * Data Contract: [createResourceApk] requires at least one overlay entry; [toDeployItems] exports a single asset item named [BuildConfig.RESOURCE_APK_NAME].
 */
class ResourceApkModifier(
    private val originApkPath: String,
    private val resourceApkFile: File,
    logger: Logger,
) {

    private val logger = logger.getInstance("ResourceApkModifier")

    fun createResourceApk(overlays: List<DeployItem>) {
        logger.debug("Create resource APK start, ${payloadStats(overlays)}, ${heapStats()}")
        updateAtomically(copyPublishedApk = false) { tempApk ->
            ZipOutputStream(tempApk.outputStream()).use { os ->
                val overlay = overlays.first() // must include one entry to create a normal ZIP file
                val entry = ZipEntry(overlay.name)
                os.putNextEntry(entry)
                os.write(overlay.content)
                os.closeEntry()
            }
            if (overlays.size > 1) {
                updateResourceApk(tempApk, overlays.subList(1, overlays.size))
            }
        }
        logger.debug("Create resource APK finished, apkBytes=${resourceApkFile.length()}, ${heapStats()}")
    }

    fun incrementalUpdateResourceApk(overlays: List<DeployItem>) {
        if (overlays.isEmpty()) {
            return
        }
        logger.debug("Update resource APK start, ${payloadStats(overlays)}, " +
                "publishedApkBytes=${resourceApkFile.length()}, ${heapStats()}")
        updateAtomically(copyPublishedApk = true) { tempApk ->
            updateResourceApk(tempApk, overlays)
        }
        logger.debug("Update resource APK finished, apkBytes=${resourceApkFile.length()}, ${heapStats()}")
    }

    private fun updateResourceApk(apkFile: File, overlays: List<DeployItem>) {
        val apkFileModifier = ApkFileModifier(apkFile, SigningConfig.EMPTY, File(""), logger)
        overlays.forEach { overlay ->
            apkFileModifier.addFile(overlay.name, overlay.content)
        }
        apkFileModifier.updateDirectly()
    }

    fun toDeployItems(): List<DeployItem> {
        logger.debug("Export resource APK start, apkBytes=${resourceApkFile.length()}, ${heapStats()}")
        val content = resourceApkFile.readBytes()
        logger.debug("Export resource APK finished, contentBytes=${content.size}, ${heapStats()}")
        val crc32 = CRC32().let {
            it.update(content)
            it.value
        }
        val deployItem = DeployItem(
            BuildConfig.RESOURCE_APK_NAME,
            CompileOutput.Type.Asset,
            crc32,
            content,
            originApkPath,
        )
        return listOf(deployItem)
    }

    private fun updateAtomically(copyPublishedApk: Boolean, update: (File) -> Unit) {
        resourceApkFile.parentFile.mkdirs()
        val tempApk = Files.createTempFile(
            resourceApkFile.parentFile.toPath(),
            ".${resourceApkFile.name}.",
            ".tmp",
        ).toFile()
        try {
            if (copyPublishedApk) {
                resourceApkFile.copyTo(tempApk, overwrite = true)
            }
            update(tempApk)
            publish(tempApk)
        } finally {
            if (tempApk.exists() && !tempApk.delete()) {
                logger.debug("Delete temporary resource APK failed: $tempApk")
            }
        }
    }

    private fun publish(tempApk: File) {
        try {
            Files.move(
                tempApk.toPath(),
                resourceApkFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempApk.toPath(), resourceApkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun payloadStats(overlays: List<DeployItem>): String {
        val totalBytes = overlays.sumOf { it.content.size.toLong() }
        val maxEntryBytes = overlays.maxOfOrNull { it.content.size } ?: 0
        return "entryCount=${overlays.size}, contentBytes=$totalBytes, maxEntryBytes=$maxEntryBytes"
    }

    private fun heapStats(): String {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return "heapUsedBytes=$usedBytes, heapMaxBytes=${runtime.maxMemory()}"
    }
}
