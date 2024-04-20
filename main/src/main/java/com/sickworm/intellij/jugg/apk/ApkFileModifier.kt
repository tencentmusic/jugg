package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.SigningConfig
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.logger.TimeLogger
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

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
        TimeLogger.start("insertFiles")
        tmpUpdateApkFile.delete()

        val remainInsertFiles: Map<String, ByteArray> = insertFiles.associate { it.first to it.second }.toMutableMap()
        val buf = ByteArray(4096)

        ZipInputStream(FileInputStream(apkFile)).use { oldApkStream ->
            ZipOutputStream(FileOutputStream(tmpUpdateApkFile)).use { newApkStream ->
                var entry = oldApkStream.nextEntry
                while (entry != null) {
                    newApkStream.putNextEntry(ZipEntry(entry.name))

                    val replaceContent = remainInsertFiles[entry.name]
                    if (replaceContent != null) {
                        newApkStream.write(replaceContent)
                    } else {
                        var len: Int
                        while ((oldApkStream.read(buf).also { len = it }) > 0) {
                            newApkStream.write(buf, 0, len)
                        }
                    }

                    newApkStream.closeEntry()
                    entry = oldApkStream.nextEntry
                }
            }
        }

        TimeLogger.end("copyAndInsertFiles", logger)
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
            throw IllegalStateException("resign APK failed, exit code: $exitCode")
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
