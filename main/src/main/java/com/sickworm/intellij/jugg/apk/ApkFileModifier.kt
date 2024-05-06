package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.SigningConfig
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.logger.TimeLogger
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class ApkFileModifier(
    private val apkFile: File,
    private val signConfig: SigningConfig,
    private val androidHome: File,
    private val logger: Logger,
) {

    private val tmpUpdateApkFile = File(apkFile.parentFile, ".${apkFile.name}.tmp_updated")
    private val tmpAlignedApkFile = File(apkFile.parentFile, ".${apkFile.name}.tmp_aligned")
    private val insertFiles = mutableListOf<Pair<String, ByteArray>>()

    private val buildToolsFolder: File by lazy {
        val buildToolsFolder = File(androidHome, "build-tools").listFiles()
            ?.filter {
                it.listFiles()?.any { subDir ->
                    subDir.nameWithoutExtension == "zipalign"
                } == true
            }?.maxBy {
                it.name
            }
        if (buildToolsFolder?.exists() != true) {
            throw IllegalStateException("Can't find build-tools folder in $androidHome")
        }
        return@lazy buildToolsFolder
    }

    fun addFile(path: String, content: ByteArray): ApkFileModifier {
        insertFiles.add(path to content)
        return this
    }

    fun insertAndResign() {
        TimeLogger.start("insertAndResign")
        copyAndInsertFiles()
        alignApk()
        resignApk()
        replaceOldApk()
        TimeLogger.end("insertAndResign", logger)
    }

    private fun copyAndInsertFiles() {
        TimeLogger.start("copyApkFile")
        tmpUpdateApkFile.delete()
        apkFile.copyTo(tmpUpdateApkFile)
        TimeLogger.end("copyApkFile", logger)

        TimeLogger.start("insertFiles")
        val zipProperties = mapOf("create" to "false")
        val zipDisk: URI = URI.create("jar:" + tmpUpdateApkFile.toURI().toString())
        FileSystems.newFileSystem(zipDisk, zipProperties).use { zipFileSystem ->
            insertFiles.forEach { (path, content) ->
                val pathInZipFile: Path = zipFileSystem.getPath(path)
                Files.delete(pathInZipFile)
                Files.copy(content.inputStream(), pathInZipFile)
            }
        }
        TimeLogger.end("insertFiles", logger)
    }

    private fun alignApk() {
        TimeLogger.start("alignApk")
        // see: https://developer.android.com/tools/zipalign
        val zipalign = File(buildToolsFolder, "zipalign").absolutePath
        val cmdString = "$zipalign -f 4 ${tmpUpdateApkFile.absolutePath} ${tmpAlignedApkFile.absolutePath}"
        val cmd = SimpleSshCommand(cmdString, logger)
        val exitCode = CmdExecutor(cmd.terminalOutputListener, cmd.logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("zipalign failed, exit code: $exitCode")
        }
        TimeLogger.end("alignApk", logger)
    }

    private fun resignApk() {
        TimeLogger.start("resignApk")
        // see: https://developer.android.com/tools/apksigner
        val apksigner = File(buildToolsFolder, "apksigner").absolutePath
        val args = mutableListOf<String>()
        args.add("sign")
        args.add("-v")
        args.add("--ks")
        args.add(signConfig.keystore!!.absolutePath) // we have checked keystore is not null before resign
        args.add("--ks-pass")
        args.add("pass:${signConfig.storePassword}")
        if (signConfig.keyAlias != null) {
            args.add("--ks-key-alias")
            args.add(signConfig.keyAlias.toString())
        }
        args.add(tmpAlignedApkFile.absolutePath)

        val cmdString = "$apksigner ${args.joinToString(" ")}"
        val cmd = SimpleSshCommand(cmdString, logger, isSecureCommand = true)
        val exitCode = CmdExecutor(cmd.terminalOutputListener, cmd.logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("AndroidManifest.xml changed and resign APK failed, exit code: $exitCode")
        }
        TimeLogger.end("resignApk", logger)
    }

    private fun replaceOldApk() {
        TimeLogger.start("replaceApk")
        apkFile.delete()
        tmpAlignedApkFile.renameTo(apkFile)
        tmpUpdateApkFile.delete()
        TimeLogger.end("replaceApk", logger)
    }

    fun clearOnError() {
        tmpAlignedApkFile.delete()
        tmpUpdateApkFile.delete()
    }

    fun verify() {
        TimeLogger.start("verifyApk")
        // see: https://developer.android.com/tools/apksigner
        val apksigner = File(buildToolsFolder, "apksigner").absolutePath
        val cmdString = "$apksigner verify ${apkFile.absolutePath}"
        val cmd = SimpleSshCommand(cmdString, logger)
        val exitCode = CmdExecutor(cmd.terminalOutputListener, cmd.logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("verify APK failed, exit code: $exitCode")
        }
        TimeLogger.end("verifyApk", logger)
    }
}
