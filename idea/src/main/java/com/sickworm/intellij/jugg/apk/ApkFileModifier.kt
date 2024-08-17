package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.exists
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.SigningConfig
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
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
            }?.maxByOrNull {
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
        // ref: https://docs.oracle.com/en/java/javase/14/docs/api/jdk.zipfs/module-summary.html
        // use FileSystems API can reduce cost time to 1-2s, while use standard ZIP API will cost 40-50s
        // compressionMethod is supported from JDK 14, because
        // https://docs.oracle.com/en/java/javase/13/docs/api/jdk.zipfs/module-summary.html doesn't have compressionMethod

        // Android Chipmunk doesn't support compressionMethod will get error
        // INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED when installing APK
        // JellyFish and after version is compatible with compressionMethod
        // D to I haven't tested
        val jvmVersion = Runtime.version().version()
        logger.debug("JVM version: $jvmVersion")
        if (jvmVersion[0] >= 14) {
            insertFileJvm14()
        } else {
            logger.warn("JVM version is ${jvmVersion[0]}, use standard ZIP API to update Zip files.")
            logger.warn("It will cost 10-60s to finished, please upgrade to Android Studio JellyFish or later to reduce 90% cost time.")
            insertFileUnderJvm14()
        }
        TimeLogger.end("insertFiles", logger)
    }

    private fun insertFileJvm14() {
        val zipProperties = mapOf("create" to "false", "compressionMethod" to "STORED")

        val zipDisk: URI = URI.create("jar:" + tmpUpdateApkFile.toURI().toString())
        FileSystems.newFileSystem(zipDisk, zipProperties).use { zipFileSystem ->
            insertFiles.forEach { (path, content) ->
                val pathInZipFile: Path = zipFileSystem.getPath(path)
                if (pathInZipFile.exists()) {
                    Files.delete(pathInZipFile)
                }
                if (pathInZipFile.parent != null && !pathInZipFile.parent.exists()) {
                    Files.createDirectories(pathInZipFile.parent)
                }
                Files.copy(content.inputStream(), pathInZipFile)
            }
        }
    }

    private fun insertFileUnderJvm14() {
        tmpUpdateApkFile.delete()

        val remainInsertFiles: MutableMap<String, ByteArray> = insertFiles.associate { it.first to it.second }.toMutableMap()
        val buf = ByteArray(4096)

        ZipInputStream(FileInputStream(apkFile)).use { oldApkStream ->
            ZipOutputStream(FileOutputStream(tmpUpdateApkFile)).use { newApkStream ->
                var entry = oldApkStream.nextEntry
                while (entry != null) {
                    // ZipInputStream will ready some empty entries, and ZipFile.entries() will not
                    if (entry.name.isNullOrEmpty()) {
                        entry = oldApkStream.nextEntry
                        continue
                    }

                    val replaceContent = remainInsertFiles[entry.name]
                    if (replaceContent != null) {
                        val newEntry = ZipEntry(entry)
                        newEntry.size = replaceContent.size.toLong()
                        newEntry.crc = CRC32().run {
                            reset()
                            update(replaceContent)
                            value
                        }
                        newApkStream.putNextEntry(newEntry)
                        newApkStream.write(replaceContent)
                        remainInsertFiles.remove(entry.name)
                    } else {
                        newApkStream.putNextEntry(ZipEntry(entry))
                        var len: Int
                        while ((oldApkStream.read(buf).also { len = it }) > 0) {
                            newApkStream.write(buf, 0, len)
                        }
                    }

                    newApkStream.closeEntry()
                    entry = oldApkStream.nextEntry
                }

                remainInsertFiles.forEach {
                    newApkStream.putNextEntry(ZipEntry(it.key))
                    newApkStream.write(it.value)
                    newApkStream.closeEntry()
                }
            }
        }
    }

    private fun alignApk() {
        TimeLogger.start("alignApk")
        // see: https://developer.android.com/tools/zipalign
        val zipalign = File(buildToolsFolder, "zipalign").absolutePath
        val cmdString = "$zipalign -f 4 ${tmpUpdateApkFile.absolutePath} ${tmpAlignedApkFile.absolutePath}"
        val cmd = SimpleSshCommand(cmdString, logger, outputFilter = { !it.endsWith("header mismatch") })
        val exitCode = CmdExecutor(cmd.logger).invoke(cmd)
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
        val exitCode = CmdExecutor(cmd.logger).invoke(cmd)
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
        val exitCode = CmdExecutor(cmd.logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("verify APK failed, exit code: $exitCode")
        }
        TimeLogger.end("verifyApk", logger)
    }
}
