package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
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

/**
 * ApkFileModifier applies in-place APK file updates and optional align/sign/replace steps.
 * Collaboration: Used by [ResourceApkModifier.incrementalUpdateResourceApk] and incremental deploy flows, delegating shell execution to [CmdExecutor.invoke].
 * Data Contract: [addFile] appends path-content pairs, and [insertAndResign] performs update -> align -> sign -> replace on the target APK.
 */
class ApkFileModifier(
    private val apkFile: File,
    private val signConfig: SigningConfig,
    private val androidHome: File,
    private val logger: Logger,
    private val envArray: List<String>? = null,
) {

    private val insertFiles = mutableListOf<Pair<String, ByteArray>>()

    private val buildToolsFolder: File by lazy {
        val buildToolsFolder = File(androidHome, "build-tools").listFiles()
            ?.filter {
                if (!it.isDirectory) {
                    return@filter false
                }
                it.listFiles()?.any { subDir ->
                    subDir.nameWithoutExtension == "zipalign"
                } == true
            }?.maxByOrNull {
                BuildToolsVersionComparator(it.name)
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
        var tmpApkFile = updateFiles(apkFile)
        tmpApkFile = alignApk(tmpApkFile)
        tmpApkFile = resignApk(tmpApkFile)
        replaceOldApk(tmpApkFile, apkFile)
        TimeLogger.end("insertAndResign", logger)
    }

    fun updateDirectly() {
        TimeLogger.start("updateDirectly")
        val tmpApkFile = updateFiles(apkFile)
        replaceOldApk(tmpApkFile, apkFile)
        TimeLogger.end("updateDirectly", logger)
    }

    private fun updateFiles(apkFile: File): File {
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
        val tmpApkFile = if (jvmVersion[0] >= 14) {
            insertFileJvm14(apkFile)
        } else {
            logger.warn("JVM version is ${jvmVersion[0]}, use standard ZIP API to update Zip files.")
            logger.warn("It will cost 10-60s to finished, please upgrade to Android Studio JellyFish or later to reduce 90% cost time.")
            insertFileUnderJvm14(apkFile)
        }
        val costTime = TimeLogger.end("insertFiles", logger)
        logger.info(" * Update APK finished, cost $costTime ms.")

        return tmpApkFile
    }

    private fun insertFileJvm14(apkFileToUpdate: File): File {
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

    private fun insertFileUnderJvm14(apkFile: File): File {
        val tmpUpdateApkFile = File(apkFile.parentFile, ".${apkFile.name}.tmp_updated")
        if (tmpUpdateApkFile.exists() && !tmpUpdateApkFile.delete()) {
            throw IllegalStateException("delete $tmpUpdateApkFile failed")
        }
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

    private fun alignApk(tmpUpdateApkFile: File): File {
        TimeLogger.start("alignApk")
        val tmpAlignedApkFile = File(apkFile.parentFile, ".${apkFile.name}.tmp_aligned")
        if (tmpAlignedApkFile.exists() && !tmpAlignedApkFile.delete()) {
            throw IllegalStateException("delete $tmpAlignedApkFile failed")
        }

        // see: https://developer.android.com/tools/zipalign
        val zipalign = File(buildToolsFolder, "zipalign").absolutePath
        val cmdString = "$zipalign -f 4 ${tmpUpdateApkFile.absolutePath} ${tmpAlignedApkFile.absolutePath}"
        val cmd = SimpleSshCommand(cmdString, outputFilter = { line, _ -> !line.endsWith("header mismatch") })
        val exitCode = CmdExecutor(logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("zipalign failed, exit code: $exitCode")
        }
        val costTime = TimeLogger.end("alignApk", logger)
        logger.info(" * Align APK finished, cost $costTime ms.")
        return tmpAlignedApkFile
    }

    private fun resignApk(tmpApkFile: File): File {
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
        args.add(tmpApkFile.absolutePath)

        val cmdString = "$apksigner ${args.joinToString(" ")}"
        val cmdStringSafeForPrint = cmdString
            .replace(signConfig.storePassword ?: "null", "***")
            .replace(signConfig.keyAlias ?: "null", "***")
            .replace(signConfig.keyPassword ?: "null", "***")
        logger.debug("signConfig storeType: ${signConfig.storeType}, cmdString: $cmdStringSafeForPrint")

        doResign(cmdString)
        val costTime = TimeLogger.end("signApk", logger)
        logger.info(" * Sign APK finished, cost $costTime ms.")

        return tmpApkFile
    }

    private fun doResign(cmdString: String) {
        val availableJdksForSign = PlatformApi.allAvailableJavaHomes().filter { javaHome ->
            if (envArray == null) {
                return@filter true
            }
            !envArray.contains("JAVA_HOME=$javaHome")
        }

        var isLastTry = availableJdksForSign.isEmpty()
        val outputFilter: ((String, Boolean) -> Boolean) = outputFilter@{ output: String, isError: Boolean ->
            if (!isLastTry) {
                logger.debug(output)
                return@outputFilter false
            }
            return@outputFilter true
        }

        val cmd = SimpleSshCommand(cmdString, isSecureCommand = true, outputFilter = outputFilter)
        val exitCode = CmdExecutor(logger).invoke(cmd, envArray)
        if (exitCode == 0) {
            logger.debug("doResign success")
            return
        }

        // Oops, apksigner failed maybe JDK is incorrect. try all available JDKs
        logger.debug("doResign failed, exit code: $exitCode, try to resign with all available JDKs: $availableJdksForSign")
        if (availableJdksForSign.isEmpty()) {
            logger.debug("doResign failed, exit code: $exitCode, no JDKs available for resign")
        } else {
            availableJdksForSign.forEachIndexed { index, javaHome ->
                logger.debug("doResign try JAVA_HOME: $javaHome")
                if (envArray != null && envArray.contains("JAVA_HOME=$javaHome")) {
                    logger.debug("doResign try skip for already try")
                    return@forEachIndexed
                }
                isLastTry = index == availableJdksForSign.size - 1
                val newEnvArray = replaceJavaHome(envArray, javaHome)
                val newExitCode = CmdExecutor(logger).invoke(cmd, newEnvArray)
                if (newExitCode == 0) {
                    logger.debug("doResign try success with JAVA_HOME: $javaHome")
                    return
                }
            }
        }

        logger.debug("doResign failed after all try")
        throw IllegalStateException("AndroidManifest.xml changed and resign APK failed, exit code: $exitCode")
    }

    private fun replaceJavaHome(envArray: List<String>?, jdkPath: String): List<String> {
        if (envArray == null) {
            return listOf("JAVA_HOME=$jdkPath")
        }
        return envArray.map {
            if (it.startsWith("JAVA_HOME=")) {
                "JAVA_HOME=$jdkPath"
            } else {
                it
            }
        }
    }

    private fun replaceOldApk(tmpApkFile: File, outputFile: File) {
        TimeLogger.start("replaceApk")
        if (tmpApkFile != outputFile) {
            if (outputFile.exists()) {
                if (!outputFile.delete()) {
                    throw IllegalStateException("Delete $outputFile failed")
                }
            }
            if (!tmpApkFile.renameTo(outputFile)) {
                throw IllegalStateException("Rename $tmpApkFile to $outputFile failed")
            }
        } else {
            logger.debug("replaceOldApk skipped, apk file not changed")
        }
        TimeLogger.end("replaceApk", logger)
    }

    fun verify() {
        TimeLogger.start("verifyApk")
        // see: https://developer.android.com/tools/apksigner
        val apksigner = File(buildToolsFolder, "apksigner").absolutePath
        val cmdString = "$apksigner verify ${apkFile.absolutePath}"
        val cmd = SimpleSshCommand(cmdString, logger)
        val exitCode = CmdExecutor(logger).invoke(cmd)
        if (exitCode != 0) {
            throw IllegalStateException("verify APK failed, exit code: $exitCode")
        }
        TimeLogger.end("verifyApk", logger)
    }
}
