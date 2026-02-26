package com.sickworm.intellij.jugg.mcp.actions

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * EmulatorListMcpToolAction implements MCP tool `emulator_list` and converts request arguments into tool execution and MCP result payloads.
 */
class EmulatorListMcpToolAction : McpToolAction {
    companion object {
        private const val HOST_ERROR_MAX_CHARS = 300
    }

    override val toolName: String = "emulator_list"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "List available Android Virtual Devices (AVDs) from host SDK.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "avds" to McpJsonSchemaProperty(
                            type = "array",
                            items = McpJsonSchemaProperty(
                                type = "object",
                                properties = mapOf(
                                    "name" to McpJsonSchemaProperty(type = "string"),
                                    "isRunning" to McpJsonSchemaProperty(type = "boolean"),
                                    "serial" to McpJsonSchemaProperty(type = "string"),
                                ),
                                required = listOf("name", "isRunning"),
                                additionalProperties = false,
                            )
                        )
                    ),
                    required = listOf("avds"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return emulatorListAction()
    }

    private fun emulatorListAction(): McpToolResult {
        val emulatorBin = findEmulatorExecutablePath()
        val avdListResult = runHostCommand(listOf(emulatorBin, "-list-avds"), 10)
        if (avdListResult.exitCode != 0) {
            val hostErrorSummary = summarizeHostError(avdListResult.stderr.ifBlank { avdListResult.stdout })
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "emulator_list failed. Reason: unable to list AVDs. $hostErrorSummary",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        val avdNames = avdListResult.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val running = queryOnlineEmulatorSerials(findAdbExecutablePath())
        val items = avdNames.mapIndexed { index, name ->
            val serial = running.getOrNull(index)
            val row = mutableMapOf<String, Any>(
                "name" to name,
                "isRunning" to (serial != null),
            )
            if (serial != null) {
                row["serial"] = serial
            }
            row
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "emulator_list executed successfully.",
            data = mapOf("avds" to items),
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    /**
     * HostCommandResult carries exitCode, stdout, and stderr.
     */
    private data class HostCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun summarizeHostError(output: String): String {
        if (output.isBlank()) {
            return "unknown host error"
        }
        val normalized = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (normalized.length <= HOST_ERROR_MAX_CHARS) {
            return normalized
        }
        return normalized.take(HOST_ERROR_MAX_CHARS) + "...[truncated]"
    }

    private fun runHostCommand(command: List<String>, timeoutSec: Long, detach: Boolean = false): HostCommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            if (detach) {
                return HostCommandResult(exitCode = 0, stdout = "", stderr = "")
            }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return HostCommandResult(exitCode = 124, stdout = "", stderr = "timeout")
            }

            HostCommandResult(
                exitCode = process.exitValue(),
                stdout = String(process.inputStream.readAllBytes()).trim(),
                stderr = String(process.errorStream.readAllBytes()).trim(),
            )
        } catch (e: Exception) {
            HostCommandResult(exitCode = 1, stdout = "", stderr = e.message ?: "unknown error")
        }
    }

    private fun queryOnlineEmulatorSerials(adbBin: String): List<String> {
        val result = runHostCommand(listOf(adbBin, "devices"), 8)
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("emulator-") && it.endsWith("\tdevice") }
            .map { it.substringBefore('\t') }
            .toList()
    }

    private fun getAndroidHomePathViaIdeaApi(): String? {
        return try {
            PlatformApi.getAndroidHomePath(Logger.getInstance("McpActionRuntime"))
        } catch (_: Exception) {
            null
        }
    }

    private fun findEmulatorExecutablePath(): String {
        val candidates = mutableListOf<File>()
        val androidHomeCandidates = listOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).filterNotNull()

        androidHomeCandidates.forEach { home ->
            candidates += File(home, "emulator/emulator")
            candidates += File(home, "emulator/emulator.exe")
        }

        val compileSdkHome = getAndroidHomePathViaIdeaApi()
        if (!compileSdkHome.isNullOrBlank()) {
            candidates += File(compileSdkHome, "emulator/emulator")
            candidates += File(compileSdkHome, "emulator/emulator.exe")
        }

        return candidates.firstOrNull { it.exists() && it.canExecute() }?.absolutePath
            ?: candidates.firstOrNull { it.exists() }?.absolutePath
            ?: "emulator"
    }

    private fun findAdbExecutablePath(): String {
        val candidates = mutableListOf<File>()
        val androidHomeCandidates = listOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).filterNotNull()

        androidHomeCandidates.forEach { home ->
            candidates += File(home, "platform-tools/adb")
            candidates += File(home, "platform-tools/adb.exe")
        }

        val compileSdkHome = getAndroidHomePathViaIdeaApi()
        if (!compileSdkHome.isNullOrBlank()) {
            candidates += File(compileSdkHome, "platform-tools/adb")
            candidates += File(compileSdkHome, "platform-tools/adb.exe")
        }

        return candidates.firstOrNull { it.exists() && it.canExecute() }?.absolutePath
            ?: candidates.firstOrNull { it.exists() }?.absolutePath
            ?: "adb"
    }
}
