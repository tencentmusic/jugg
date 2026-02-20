package com.sickworm.intellij.jugg.deploy.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggException
import io.github.classgraph.ClassGraph
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * APK parser process launcher
 *
 * Responsible for launching an isolated process to parse APKs, avoiding high memory usage in the IDE main process
 */
class ApkParserProcessLauncher(
    private val logger: Logger
) {
    private val gson = Gson()

    companion object {
        // Timeout: 10 minutes
        private const val PROCESS_TIMEOUT_MINUTES = 10L

        // APK size threshold: 0MB, use isolated process if exceeds this size. Currently enabled by default
        private const val ISOLATED_PROCESS_THRESHOLD_MB = 0L
    }

    /**
     * Determine whether to use an isolated process
     */
    fun shouldUseIsolatedProcess(apkFiles: List<ApkFileUnit>): Boolean {
        val totalSize = apkFiles.sumOf { it.apkFile.length() }
        val totalSizeMB = totalSize / (1024 * 1024)
        logger.debug("Total APK size: ${totalSizeMB}MB, threshold: ${ISOLATED_PROCESS_THRESHOLD_MB}MB")
        return totalSizeMB > ISOLATED_PROCESS_THRESHOLD_MB
    }

    /**
     * Parse APK in an isolated process
     */
    fun parseInIsolatedProcess(
        dbDir: File,
        apkFiles: List<ApkFileUnit>,
        applicationId: String
    ): List<ParsedApkUpdateResult> {
        val outputFile = File.createTempFile("apk_parse_result_", ".json")

        try {
            logger.info("Starting isolated process to parse APK files...")
            logger.info("APK files: ${apkFiles.map { it.apkFile.name }}")

            // Serialize input parameters
            val apkFilesJson = serializeApkFiles(apkFiles)

            // Build process command
            val command = buildProcessCommand(dbDir, apkFilesJson, applicationId, outputFile)
            logger.debug("Process command: ${command.joinToString(" ")}")

            // Start process
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            // Monitor process output
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val outputThread = Thread {
                try {
                    outputReader.useLines { lines ->
                        lines.forEach { line ->
                            if (line.startsWith("[INFO]") || line.startsWith("[DEBUG]")) {
                                logger.debug(line)
                            } else if (line.startsWith("[WARN]")) {
                                logger.warn(line)
                            } else if (line.startsWith("[ERROR]")) {
                                logger.error(line)
                            } else {
                                logger.debug(line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Error reading process output: ${e.message}")
                }
            }
            outputThread.start()

            // Wait for process to complete
            val finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)

            if (!finished) {
                process.destroyForcibly()
                throw JuggException("APK parsing process timeout after $PROCESS_TIMEOUT_MINUTES minutes")
            }

            val exitCode = process.exitValue()
            logger.info("Process exited with code: $exitCode")

            if (exitCode != 0) {
                val errorContent = if (outputFile.exists()) outputFile.readText() else "No output file"
                throw JuggException("APK parsing process failed with exit code $exitCode: $errorContent")
            }

            // Deserialize results
            if (!outputFile.exists()) {
                throw JuggException("Output file not found: ${outputFile.absolutePath}")
            }

            val resultJson = outputFile.readText()
            return deserializeResults(resultJson)

        } catch (e: JuggException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to parse APK in isolated process", e)
            throw JuggException("Failed to parse APK in isolated process: ${e.message}")
        } finally {
            if (outputFile.exists()) {
                outputFile.delete()
            }
        }
    }

    private fun buildProcessCommand(
        dbDir: File,
        apkFilesJson: String,
        applicationId: String,
        outputFile: File
    ): List<String> {
        // Get Java Home
        val javaHomes = PlatformApi.allAvailableJavaHomes()
        if (javaHomes.isEmpty()) {
            throw JuggException("No Java home found")
        }
        val javaHome = javaHomes.first()
        val javaExecutable = File(javaHome, "bin/java").absolutePath

        // Get classpath
        val classpathUrls = ClassGraph().classpathURLs
        val classpath = classpathUrls.joinToString(File.pathSeparator) { it.path }

        return listOf(
            javaExecutable,
            "-Xmx8g", // Isolated process can use larger heap memory
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=200",
            "-cp", classpath,
            "com.sickworm.intellij.jugg.deploy.data.ApkParserProcess",
            dbDir.absolutePath,
            apkFilesJson,
            applicationId,
            outputFile.absolutePath
        )
    }

    private fun serializeApkFiles(apkFiles: List<ApkFileUnit>): String {
        val dtos = apkFiles.map { unit ->
            ApkFileUnitDto(
                applicationId = unit.applicationId,
                moduleName = unit.moduleName,
                debuggable = unit.debuggable,
                apkFilePath = unit.apkFile.absolutePath
            )
        }
        return gson.toJson(dtos)
    }

    private fun deserializeResults(json: String): List<ParsedApkUpdateResult> {
        // Check if it's an error response
        try {
            val errorMap: Map<String, Any> = gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
            if (errorMap["error"] == true) {
                val message = errorMap["message"] as? String ?: "Unknown error"
                throw JuggException("APK parsing failed: $message")
            }
        } catch (e: JuggException) {
            throw e
        } catch (e: Exception) {
            // Not an error response, continue parsing normal results
        }

        val type = object : TypeToken<List<ParsedApkUpdateResultDto>>() {}.type
        val dtos: List<ParsedApkUpdateResultDto> = gson.fromJson(json, type)

        return dtos.map { dto ->
            val apkFile = File(dto.apkFilePath)
            val diffResult = if (dto.isSuccess && dto.apkFilePath.isNotEmpty()) {
                // Create a simplified diffResult containing only statistics
                ParsedApkDiffResult(
                    apkFile = apkFile,
                    updatedApkInfos = dto.updatedApkInfos,
                    updatedDexFiles = emptyMap(), // Actual file mappings have been processed in the isolated process
                    updatedOverlayFiles = emptyMap()
                )
            } else {
                null
            }

            ParsedApkUpdateResult(
                isSuccess = dto.isSuccess,
                errorMessage = dto.errorMessage,
                diffResult = diffResult,
                addedClasses = dto.addedClasses,
                removedClasses = dto.removedClasses,
                updatedClasses = dto.updatedClasses
            )
        }
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
}
