package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.data.SigningConfig
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class ResourceApkModifier(
    private val originApkPath: String,
    private val resourceApkFile: File,
    logger: Logger,
) {

    private val logger = logger.getInstance("ResourceApkModifier")

    fun createResourceApk(overlays: List<DeployItem>) {
        resourceApkFile.delete()
        resourceApkFile.parentFile.mkdirs()
        resourceApkFile.createNewFile()
        ZipOutputStream(resourceApkFile.outputStream()).use { os ->
            val overlay = overlays.first() // must include one entry to create a normal ZIP file
            val entry = ZipEntry(overlay.name)
            os.putNextEntry(entry)
            os.write(overlay.content)
            os.closeEntry()
        }
        // write other overlays into ZIP file
        if (overlays.size > 1) {
            incrementalUpdateResourceApk(overlays.subList(1, overlays.size))
        }
    }

    fun incrementalUpdateResourceApk(overlays: List<DeployItem>) {
        val apkFileModifier = ApkFileModifier(resourceApkFile, SigningConfig.EMPTY, File(""), logger)
        overlays.forEach { overlay ->
            apkFileModifier.addFile(overlay.name, overlay.content)
        }
        apkFileModifier.updateDirectly()
    }

    fun toDeployItems(): List<DeployItem> {
        val content = resourceApkFile.readBytes()
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
}