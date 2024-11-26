package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
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
import kotlin.io.path.exists

class ApkFileModifier(
    private val apkFile: File,
    private val signConfig: SigningConfig,
    private val androidHome: File,
    private val logger: Logger,
    private val envArray: List<String>? = null,
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
        clearTempFile()
        val apkFile = updateFiles()
        alignApk(apkFile)
        resignApk()
        replaceOldApk()
        clearTempFile()
        TimeLogger.end("insertAndResign", logger)
    }

    fun updateDirectly() {
        TimeLogger.start("updateDirectly")
        clearTempFile()
        updateFiles()
        replaceOldApk()
        clearTempFile()
        TimeLogger.end("updateDirectly", logger)
    }

    private fun updateFiles(): File {
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
        val apkFile = if (jvmVersion[0] >= 14) {
            insertFileJvm14()
        } else {
            logger.warn("JVM version is ${jvmVersion[0]}, use standard ZIP API to update Zip files.")
            logger.warn("It will cost 10-60s to finished, please upgrade to Android Studio JellyFish or later to reduce 90% cost time.")
            insertFileUnderJvm14()
        }
        val costTime = TimeLogger.end("insertFiles", logger)
        logger.info(" * Update APK finished, cost $costTime ms.")

        return apkFile
    }

    private fun insertFileJvm14(): File {
        val apkFileToUpdate = if (isWindows) {
            // sometimes will get "The process cannot access the file because it is being used by another process" on ZipFileSystem.close()
            // so copy it out
            tmpUpdateApkFile.delete()
            apkFile.copyTo(tmpUpdateApkFile)
            tmpUpdateApkFile
        } else {
            apkFile
        }

        val zipProperties = mapOf("create" to "false", "compressionMethod" to "STORED")

        val zipDisk: URI = URI.create("jar:" + apkFileToUpdate.toURI().toString())
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

        return apkFileToUpdate
    }

    private fun insertFileUnderJvm14(): File {
        tmpUpdateApkFile.delete()
        apkFile.copyTo(tmpUpdateApkFile)

        val remainInsertFiles: MutableMap<String, ByteArray> = insertFiles.associate { it.first to it.second }.toMutableMap()
        val buf = ByteArray(4096)

        ZipInputStream(FileInputStream(apkFile)).use { oldApkStream ->
            ZipOutputStream(FileOutputStream(tmpUpdateApkFile)).use { newApkStream ->
                var entry = oldApkStream.nextEntry
                while (entry != null) {
                    // ZipInputStream will get some entry with empty name, while ZipFile.entries() will not
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

        return tmpUpdateApkFile
    }

    private fun alignApk(tmpUpdateApkFile: File) {
        TimeLogger.start("alignApk")
        // see: https://developer.android.com/tools/zipalign
        val zipalign = File(buildToolsFolder, "zipalign").absolutePath
        val cmdString = "$zipalign -f 4 ${tmpUpdateApkFile.absolutePath} ${tmpAlignedApkFile.absolutePath}"
        val cmd = SimpleSshCommand(cmdString, logger, outputFilter = { !it.endsWith("header mismatch") })
        val exitCode = CmdExecutor(cmd.logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("zipalign failed, exit code: $exitCode")
        }
        val costTime = TimeLogger.end("alignApk", logger)
        logger.info(" * Align APK finished, cost $costTime ms.")
    }

    private fun resignApk() {
        TimeLogger.start("signApk")
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
            if (signConfig.keyPassword != null) {
                args.add("--key-pass")
                args.add("pass:${signConfig.keyPassword}")
            }
        }
        args.add(tmpAlignedApkFile.absolutePath)

        val cmdString = "$apksigner ${args.joinToString(" ")}"
        val cmdStringSafeForPrint = cmdString
            .replace(signConfig.storePassword ?: "null", "***")
            .replace(signConfig.keyAlias ?: "null", "***")
            .replace(signConfig.keyPassword ?: "null", "***")
        logger.debug("signConfig storeType: ${signConfig.storeType}, cmdString: $cmdStringSafeForPrint")

        val cmd = SimpleSshCommand(cmdString, logger, isSecureCommand = true)
        val exitCode = CmdExecutor(cmd.logger).invoke(cmd, envArray)
        if (exitCode != 0) {
            throw IllegalStateException("AndroidManifest.xml changed and resign APK failed, exit code: $exitCode")
        }
        val costTime = TimeLogger.end("signApk", logger)
        logger.info(" * Sign APK finished, cost $costTime ms.")
    }

    private fun replaceOldApk() {
        TimeLogger.start("replaceApk")
        if (tmpAlignedApkFile.exists()) {
            apkFile.delete()
            tmpAlignedApkFile.renameTo(apkFile)
        }
        TimeLogger.end("replaceApk", logger)
    }

    private fun clearTempFile() {
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