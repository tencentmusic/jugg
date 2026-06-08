package com.sickworm.intellij.jugg.deploy.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import org.apache.log4j.Level
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Standalone process entry point for APK parsing
 *
 * Used to parse large APKs in an isolated process to avoid high memory usage and freezing in the IDE main process
 *
 * Arguments:
 * - args[0]: dbDir - Database directory
 * - args[1]: apkFilesJson - JSON of ApkFileUnit list
 * - args[2]: applicationId - Application ID
 * - args[3]: outputFile - Result output file path
 */
object ApkParserProcess {

    private val gson = Gson()
    private val logger = ConsoleLogger()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 4) {
            System.err.println("Usage: ApkParserProcess <dbDir> <apkFilesJson> <applicationId> <outputFile>")
            exitProcess(1)
        }

        val dbDir = File(args[0])
        val apkFilesJson = decodeApkFilesJson(args[1])
        val applicationId = args[2]
        val outputFile = File(args[3])

        try {
            logger.info("PROGRESS:0")
            logger.info("ApkParserProcess started")
            logger.info("dbDir: ${dbDir.absolutePath}")
            logger.info("applicationId: $applicationId")
            logger.info("outputFile: ${outputFile.absolutePath}")

            // Deserialize input parameters
            logger.info("PROGRESS:10")
            val apkFiles = deserializeApkFiles(apkFilesJson)
            logger.info("Parsed ${apkFiles.size} APK files")

            // Initialize database
            logger.info("PROGRESS:20")
            val dbFile = File(dbDir, "$applicationId.db")
            val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)
            helper.init()
            logger.info("Database initialized: ${dbFile.absolutePath}")

            // Execute parsing
            logger.info("PROGRESS:30")
            val results = processApkWithHelper(apkFiles, helper, logger)
            logger.info("PROGRESS:90")

            // Serialize results
            val resultJson = serializeResults(results)
            outputFile.writeText(resultJson)
            logger.info("PROGRESS:100")
            logger.info("ApkParserProcess completed successfully")

            exitProcess(0)
        } catch (e: Exception) {
            logger.error("ApkParserProcess failed: ${e.message}", e)
            val errorJson = serializeError(e)
            outputFile.writeText(errorJson)
            exitProcess(1)
        }
    }

    private fun processApkWithHelper(
        apkFiles: List<ApkFileUnit>,
        helper: DeployDataDatabaseSqLiteHelper,
        logger: Logger
    ): List<ParsedApkUpdateResult> {
        val diffBeanList = apkFiles.map { apkFileUnit ->
            val apkFile = apkFileUnit.apkFile
            logger.info("Parsing entries for ${apkFile.name}")
            val apkEntries = ApkParser().parseEntries(apkFile)
            logger.debug("${apkFile.name} apkEntries, dexFiles: ${apkEntries.dexFiles.size}, overlayFiles: ${apkEntries.overlayFiles.size}")
            val diffResult = helper.diffApk(apkEntries)
            logger.debug("${apkFile.name} diffResult $diffResult")
            DiffBean(apkEntries, diffResult)
        }.toMutableList()

        val allChangedDexFileSize = diffBeanList.sumOf { it.allChangedDexFileSize }
        val dexFileSize = diffBeanList.sumOf { it.dexFileSize }
        val isFullUpdate = allChangedDexFileSize > 3 || (dexFileSize > 0 && allChangedDexFileSize >= dexFileSize * 0.2)

        val diffs = diffBeanList.map { if (isFullUpdate) ParsedApkDiffResult(it.apkEntries) else it.diffResult }

        logger.info("PROGRESS:40")
        logger.info("Parsing APK files...")
        val parsedList = ApkParser().parse(diffs)
        logger.info("PROGRESS:70")

        if (isFullUpdate) {
            logger.info("${apkFiles.map { it.apkFile.name }} dex changes too much (${allChangedDexFileSize}/$dexFileSize), full update this APK.")
            helper.recreateDatabase()
        } else {
            logger.debug("${apkFiles.map { it.apkFile.name }} incremental update database.")
        }

        logger.info("PROGRESS:80")
        logger.info("Saving to database...")
        val result = helper.saveParsedApkBatch(parsedList, diffs)
        return result
    }

    /**
     * DiffBean carries apkEntries and diffResult.
     */
    private data class DiffBean(
        val apkEntries: ApkEntries,
        val diffResult: ParsedApkDiffResult,
    ) {
        val allChangedDexFileSize = diffResult.removedDexFiles.size + diffResult.addedDexFiles.size + diffResult.updatedDexFiles.size
        val dexFileSize = apkEntries.dexFiles.size
    }

    private fun deserializeApkFiles(json: String): List<ApkFileUnit> {
        val type = object : TypeToken<List<ApkFileUnitDto>>() {}.type
        val dtos: List<ApkFileUnitDto> = gson.fromJson(json, type)
        return dtos.map { dto ->
            ApkFileUnit(dto.applicationId, dto.moduleName, dto.debuggable, File(dto.apkFilePath))
        }
    }

    private fun decodeApkFilesJson(encodedJson: String): String {
        return String(Base64.getUrlDecoder().decode(encodedJson), Charsets.UTF_8)
    }

    private fun serializeResults(results: List<ParsedApkUpdateResult>): String {
        val dtos = results.map { result ->
            ParsedApkUpdateResultDto(
                isSuccess = result.isSuccess,
                errorMessage = result.errorMessage,
                apkFilePath = result.diffResult?.apkFile?.absolutePath ?: "",
                addedClasses = result.addedClasses,
                removedClasses = result.removedClasses,
                updatedClasses = result.updatedClasses,
                updatedApkInfos = result.diffResult?.updatedApkInfos ?: 0,
                updatedDexFiles = result.diffResult?.updatedDexFiles?.size ?: 0,
                updatedOverlayFiles = result.diffResult?.updatedOverlayFiles?.size ?: 0
            )
        }
        return gson.toJson(dtos)
    }

    private fun serializeError(e: Exception): String {
        val error = mapOf(
            "error" to true,
            "message" to (e.message ?: "Unknown error"),
            "stackTrace" to e.stackTraceToString()
        )
        return gson.toJson(error)
    }

    // DTO classes for serialization
    /**
     * ApkFileUnitDto carries applicationId, moduleName, debuggable, and apkFilePath.
     */
    private data class ApkFileUnitDto(
        val applicationId: String,
        val moduleName: String,
        val debuggable: Boolean,
        val apkFilePath: String
    )

    /**
     * ParsedApkUpdateResultDto carries isSuccess, errorMessage, apkFilePath, and addedClasses.
     */
    private data class ParsedApkUpdateResultDto(
        val isSuccess: Boolean,
        val errorMessage: String?,
        val apkFilePath: String,
        val addedClasses: List<String>,
        val removedClasses: List<String>,
        val updatedClasses: List<String>,
        val updatedApkInfos: Int,
        val updatedDexFiles: Int,
        val updatedOverlayFiles: Int
    )

    /**
     * Simple console logger implementation
     */
    private class ConsoleLogger : Logger() {
        override fun isDebugEnabled(): Boolean = true
        override fun debug(message: String?) {
            println("[DEBUG] $message")
        }
        override fun debug(t: Throwable?) {
            t?.printStackTrace()
        }
        override fun debug(message: String?, t: Throwable?) {
            println("[DEBUG] $message")
            t?.printStackTrace()
        }
        override fun info(message: String?) {
            println("[INFO] $message")
        }
        override fun info(message: String?, t: Throwable?) {
            println("[INFO] $message")
            t?.printStackTrace()
        }
        override fun warn(message: String?, t: Throwable?) {
            System.err.println("[WARN] $message")
            t?.printStackTrace()
        }
        override fun error(message: String?, t: Throwable?, vararg details: String?) {
            System.err.println("[ERROR] $message")
            t?.printStackTrace()
            details.forEach { System.err.println("[ERROR] $it") }
        }
        @Suppress("UnstableApiUsage")
        override fun setLevel(level: Level) {
            // No-op for console logger
        }
    }
}
