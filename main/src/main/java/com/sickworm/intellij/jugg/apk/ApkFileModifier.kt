package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.exists

/**
 * Escapes one shell argument so paths with spaces, quotes or Unicode characters stay a single argument.
 */
internal fun shellEscapeArgument(argument: String): String {
    return if (isWindows) {
        "\"${argument.replace("\"", "\"\"")}\""
    } else {
        "'${argument.replace("'", "'\"'\"'")}'"
    }
}

internal fun shellCommand(arguments: List<String>): String {
    return arguments.joinToString(" ") { shellEscapeArgument(it) }
}

/**
 * ApkFileModifier applies APK file updates and optional align/sign/replace steps.
 * Collaboration: Used by [ResourceApkModifier.incrementalUpdateResourceApk] and incremental deploy flows, delegating shell execution to [CmdExecutor.invoke].
 * Data Contract: [addFile] appends path-content pairs, and [insertAndResign] publishes changes only after the temporary APK is signed and verified.
 * When [customApkSignScriptRunner] is present it replaces the local keystore signing step, and [signConfig] is only required on the default path.
 */
class ApkFileModifier(
    private val apkFile: File,
    private val signConfig: SigningConfig?,
    private val androidHome: File,
    private val logger: Logger,
    private val envArray: List<String>? = null,
    private val customApkSignScriptRunner: CustomApkSignScriptRunner? = null,
) {

    private val insertFiles = mutableListOf<InsertFile>()

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
        insertFiles.add(InsertFile.fromBytes(path, content))
        return this
    }

    fun addFile(path: String, file: File, size: Long, crc: Long): ApkFileModifier {
        if (size >= CLASSIC_ZIP_ENTRY_LIMIT) {
            throw IllegalStateException("APK entry $path is $size bytes, exceeding the classic ZIP entry limit")
        }
        insertFiles.add(InsertFile.fromFile(path, file, size, crc))
        return this
    }

    fun insertAndResign() {
        TimeLogger.start("insertAndResign")
        val tmpApkFiles = mutableSetOf<File>()
        val workingApkFile = Files.createTempFile(apkFile.parentFile.toPath(), ".${apkFile.name}.", ".tmp_working").toFile()
        tmpApkFiles.add(workingApkFile)
        try {
            apkFile.copyTo(workingApkFile, overwrite = true)
            var tmpApkFile = updateFiles(workingApkFile).also(tmpApkFiles::add)
            tmpApkFile = alignApk(tmpApkFile).also(tmpApkFiles::add)
            val signEnv = if (customApkSignScriptRunner != null) {
                customApkSignScriptRunner.run(tmpApkFile)
                envArray
            } else {
                resignApk(tmpApkFile)
            }
            verifyApk(tmpApkFile, signEnv)
            replaceOldApk(tmpApkFile, apkFile)
            TimeLogger.end("insertAndResign", logger)
        } finally {
            tmpApkFiles.forEach { it.delete() }
        }
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
        val useZipFs = jvmVersion[0] >= 14 && insertFiles.none { it.isFileBacked }
        val tmpApkFile = if (useZipFs) {
            insertFileJvm14(apkFile)
        } else {
            if (jvmVersion[0] < 14) {
                logger.warn("JVM version is ${jvmVersion[0]}, use standard ZIP API to update Zip files.")
                logger.warn("It will cost 10-60s to finished, please upgrade to Android Studio JellyFish or later to reduce 90% cost time.")
            }
            insertFileUnderJvm14(apkFile, storeNewEntries = jvmVersion[0] >= 14)
        }
        val costTime = TimeLogger.end("insertFiles", logger)
        logger.info(" * Update APK finished, cost $costTime ms.")

        return tmpApkFile
    }

    private fun insertFileJvm14(apkFileToUpdate: File): File {
        val zipProperties = mapOf("create" to "false", "compressionMethod" to "STORED")

        val zipDisk: URI = URI.create("jar:" + apkFileToUpdate.toURI().toString())
        logger.debug("Open ZipFS, apkFile=$apkFileToUpdate, insertFileCount=${insertFiles.size}")
        FileSystems.newFileSystem(zipDisk, zipProperties).use { zipFileSystem ->
            insertFiles.forEach { insertFile ->
                val pathInZipFile: Path = zipFileSystem.getPath(insertFile.path)
                if (pathInZipFile.exists()) {
                    Files.delete(pathInZipFile)
                }
                if (pathInZipFile.parent != null && !pathInZipFile.parent.exists()) {
                    Files.createDirectories(pathInZipFile.parent)
                }
                Files.copy(insertFile.bytes!!.inputStream(), pathInZipFile)
            }
        }

        return apkFileToUpdate
    }

    private fun insertFileUnderJvm14(apkFile: File, storeNewEntries: Boolean): File {
        val tmpUpdateApkFile = File(apkFile.parentFile, ".${apkFile.name}.tmp_updated")
        if (tmpUpdateApkFile.exists() && !tmpUpdateApkFile.delete()) {
            throw IllegalStateException("delete $tmpUpdateApkFile failed")
        }
        apkFile.copyTo(tmpUpdateApkFile)

        val remainInsertFiles = insertFiles.associateBy { it.path }.toMutableMap()
        val buf = ByteArray(4096)

        try {
            ZipInputStream(FileInputStream(apkFile)).use { oldApkStream ->
                ZipOutputStream(FileOutputStream(tmpUpdateApkFile)).use { newApkStream ->
                    var entry = oldApkStream.nextEntry
                    while (entry != null) {
                        // ZipInputStream will get some entry with empty name, while ZipFile.entries() will not
                        if (entry.name.isNullOrEmpty()) {
                            entry = oldApkStream.nextEntry
                            continue
                        }

                        val replaceFile = remainInsertFiles.remove(entry.name)
                        if (replaceFile != null) {
                            val newEntry = ZipEntry(entry).apply {
                                size = replaceFile.size
                                crc = replaceFile.crc
                                compressedSize = -1L
                                if (storeNewEntries && !replaceFile.isFileBacked) {
                                    method = ZipEntry.STORED
                                }
                            }
                            newApkStream.putNextEntry(newEntry)
                            writeInsertFile(newApkStream, replaceFile)
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

                    remainInsertFiles.values.forEach { insertFile ->
                        if (insertFile.isFileBacked && insertFile.size > Int.MAX_VALUE) {
                            throw IllegalStateException("Large native library entry is missing from the base APK: " +
                                    "${insertFile.path}, size=${insertFile.size}")
                        }
                        val newEntry = ZipEntry(insertFile.path)
                        if (storeNewEntries) {
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = insertFile.size
                            newEntry.crc = insertFile.crc
                        }
                        newApkStream.putNextEntry(newEntry)
                        writeInsertFile(newApkStream, insertFile)
                        newApkStream.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            tmpUpdateApkFile.delete()
            throw e
        }

        return tmpUpdateApkFile
    }

    private fun writeInsertFile(output: ZipOutputStream, insertFile: InsertFile) {
        val crc = CRC32()
        CheckedInputStream(insertFile.openInputStream(), crc).use { input ->
            input.copyTo(output, DEFAULT_BUFFER_SIZE)
        }
        if (insertFile.isFileBacked) {
            insertFile.validateSource()
        }
        if (crc.value != insertFile.crc) {
            throw IllegalStateException("APK entry source changed while writing: ${insertFile.path}, " +
                    "expectedCrc=${insertFile.crc}, actualCrc=${crc.value}")
        }
    }

    private fun alignApk(tmpUpdateApkFile: File): File {
        TimeLogger.start("alignApk")
        val tmpAlignedApkFile = File(tmpUpdateApkFile.parentFile, ".${tmpUpdateApkFile.name}.tmp_aligned")
        if (tmpAlignedApkFile.exists() && !tmpAlignedApkFile.delete()) {
            throw IllegalStateException("delete $tmpAlignedApkFile failed")
        }

        // see: https://developer.android.com/tools/zipalign
        val zipalign = File(buildToolsFolder, "zipalign").absolutePath
        val cmdString = shellCommand(listOf(
            zipalign,
            "-f",
            "4",
            tmpUpdateApkFile.absolutePath,
            tmpAlignedApkFile.absolutePath,
        ))
        val cmd = SimpleSshCommand(cmdString, outputFilter = { line, _ -> !line.endsWith("header mismatch") })
        try {
            val exitCode = CmdExecutor(logger).invoke(cmd)
            if (exitCode != 0) {
                throw IllegalStateException("zipalign failed, exit code: $exitCode")
            }
            val costTime = TimeLogger.end("alignApk", logger)
            logger.info(" * Align APK finished, cost $costTime ms.")
            return tmpAlignedApkFile
        } catch (e: Exception) {
            tmpAlignedApkFile.delete()
            throw e
        }
    }

    private fun resignApk(tmpApkFile: File): List<String>? {
        TimeLogger.start("signApk")
        // see: https://developer.android.com/tools/apksigner
        val signingConfig = signConfig
            ?: throw IllegalStateException("Signing config not found for APK signing.")
        val apksigner = File(buildToolsFolder, "apksigner").absolutePath
        val args = mutableListOf<String>()
        args.add("sign")
        args.add("-v")
        args.add("--ks")
        args.add(signingConfig.keystore!!.absolutePath) // we have checked keystore is not null before resign
        args.add("--ks-pass")
        args.add("pass:${signingConfig.storePassword}")
        if (signingConfig.keyAlias != null) {
            args.add("--ks-key-alias")
            args.add(signingConfig.keyAlias.toString())
            if (signingConfig.keyPassword != null) {
                args.add("--key-pass")
                args.add("pass:${signingConfig.keyPassword}")
            }
        }
        args.add(tmpApkFile.absolutePath)

        val cmdString = shellCommand(listOf(apksigner) + args)
        val cmdStringSafeForPrint = cmdString
            .replace(signingConfig.storePassword ?: "null", "***")
            .replace(signingConfig.keyAlias ?: "null", "***")
            .replace(signingConfig.keyPassword ?: "null", "***")
        logger.debug("signConfig storeType: ${signConfig.storeType}, cmdString: $cmdStringSafeForPrint")

        val signEnv = doResign(cmdString)
        val costTime = TimeLogger.end("signApk", logger)
        logger.info(" * Sign APK finished, cost $costTime ms.")

        return signEnv
    }

    private fun doResign(cmdString: String): List<String>? {
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
            return envArray
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
                    return newEnvArray
                }
            }
        }

        logger.debug("doResign failed after all try")
        throw IllegalStateException("AndroidManifest.xml changed and resign APK failed, exit code: $exitCode")
    }

    private fun replaceJavaHome(envArray: List<String>?, jdkPath: String): List<String> {
        return envArray.orEmpty()
            .filterNot { it.startsWith("JAVA_HOME=", ignoreCase = true) }
            .plus("JAVA_HOME=$jdkPath")
    }

    private fun replaceOldApk(tmpApkFile: File, outputFile: File) {
        TimeLogger.start("replaceApk")
        if (tmpApkFile != outputFile) {
            try {
                Files.move(
                    tmpApkFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpApkFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            logger.debug("replaceOldApk skipped, apk file not changed")
        }
        TimeLogger.end("replaceApk", logger)
    }

    fun verify() {
        verifyApk(apkFile, envArray)
    }

    private fun verifyApk(apkFileToVerify: File, verifyEnv: List<String>?) {
        TimeLogger.start("verifyApk")
        // see: https://developer.android.com/tools/apksigner
        val apksigner = File(buildToolsFolder, "apksigner").absolutePath
        val cmdString = shellCommand(listOf(apksigner, "verify", apkFileToVerify.absolutePath))
        val cmd = SimpleSshCommand(cmdString, logger)
        val exitCode = CmdExecutor(logger).invoke(cmd, verifyEnv)
        if (exitCode != 0) {
            throw IllegalStateException("verify APK failed, exit code: $exitCode")
        }
        TimeLogger.end("verifyApk", logger)
    }

    private class InsertFile private constructor(
        val path: String,
        val bytes: ByteArray?,
        private val file: File?,
        val size: Long,
        val crc: Long,
        private val lastModified: Long,
    ) {

        val isFileBacked: Boolean get() = file != null

        fun openInputStream() = bytes?.inputStream() ?: validateSource().inputStream()

        fun validateSource(): File {
            val source = file ?: throw IllegalStateException("APK entry $path is not file-backed")
            if (!source.isFile || !source.canRead()) {
                throw IllegalStateException("APK entry source is unavailable: $path, file=${source.absolutePath}")
            }
            if (source.length() != size || source.lastModified() != lastModified) {
                throw IllegalStateException("APK entry source changed before writing: $path, " +
                        "expectedSize=$size, actualSize=${source.length()}")
            }
            return source
        }

        companion object {
            fun fromBytes(path: String, bytes: ByteArray): InsertFile {
                val crc = CRC32().run {
                    update(bytes)
                    value
                }
                return InsertFile(path, bytes, null, bytes.size.toLong(), crc, 0L)
            }

            fun fromFile(path: String, file: File, size: Long, crc: Long): InsertFile {
                if (!file.isFile || !file.canRead() || file.length() != size) {
                    throw IllegalStateException("APK entry source is unavailable: $path, file=${file.absolutePath}")
                }
                return InsertFile(path, null, file, size, crc, file.lastModified())
            }
        }
    }

    companion object {
        private const val CLASSIC_ZIP_ENTRY_LIMIT = 4_294_967_296L
    }
}
