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
 * StartEmulatorMcpToolAction implements MCP tool `start_emulator` and converts request arguments into tool execution and MCP result payloads.
 */
class StartEmulatorMcpToolAction : McpToolAction {
    companion object {
        private const val HOST_ERROR_MAX_CHARS = 300
    }

    override val toolName: String = "start_emulator"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Start an Android emulator (AVD) process from host environment. Use when no suitable emulator is online before deploy/verification. Avoid when a usable online device already exists. Side effects: launches emulator process on host and may change connected device list.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "avdName" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional AVD name. If absent, fallback to first available AVD from `emulator -list-avds`.",
                    pattern = "^\\S.*$",
                    examples = listOf("Pixel_8_API_35"),
                ),
                "waitForDeviceSec" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional max wait seconds for a newly booted emulator to appear as online device.",
                    default = 45,
                    minimum = 0.0,
                    maximum = 300.0,
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "avdName" to McpJsonSchemaProperty(type = "string"),
                        "started" to McpJsonSchemaProperty(type = "boolean"),
                        "waitedSec" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                        "emulatorSerial" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = listOf("avdName", "started", "waitedSec"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val waitForDeviceSec = (arguments["waitForDeviceSec"] as? Number)?.toInt()
        return startEmulatorAction(
            avdName = arguments["avdName"] as? String,
            waitForDeviceSec = waitForDeviceSec,
        )
    }

    private fun startEmulatorAction(avdName: String?, waitForDeviceSec: Int?): McpToolResult {
        val resolvedWaitSec = (waitForDeviceSec ?: 45).coerceIn(0, 300)
        val adbBin = findAdbExecutablePath()
        val emulatorBin = findEmulatorExecutablePath()

        val avdListResult = runHostCommand(listOf(emulatorBin, "-list-avds"), 10)
        if (avdListResult.exitCode != 0) {
            val hostErrorSummary = summarizeHostError(avdListResult.stderr.ifBlank { avdListResult.stdout })
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "start_emulator failed. Reason: unable to list AVDs. $hostErrorSummary",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        val avdNames = avdListResult.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (avdNames.isEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "start_emulator failed. Reason: no AVD found. Please create one in Android Studio Device Manager.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_NO_DEVICE,
            )
        }

        val resolvedAvd = if (avdName.isNullOrBlank()) avdNames.first() else avdName
        if (!avdNames.contains(resolvedAvd)) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "start_emulator failed. Reason: AVD '$resolvedAvd' not found.",
                data = mapOf("availableAvds" to avdNames),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        val beforeSerials = queryOnlineEmulatorSerials(adbBin)
        val startResult = runHostCommand(listOf(emulatorBin, "-avd", resolvedAvd), 5, detach = true)
        if (startResult.exitCode != 0) {
            val hostErrorSummary = summarizeHostError(startResult.stderr.ifBlank { startResult.stdout })
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "start_emulator failed. Reason: failed to launch emulator process. $hostErrorSummary",
                data = mapOf("avdName" to resolvedAvd),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        var discoveredSerial: String? = null
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + resolvedWaitSec * 1000L
        while (System.currentTimeMillis() <= deadline) {
            val current = queryOnlineEmulatorSerials(adbBin)
            val diff = current.firstOrNull { it !in beforeSerials }
            if (diff != null) {
                discoveredSerial = diff
                break
            }
            if (resolvedWaitSec == 0) {
                break
            }
            Thread.sleep(1000)
        }

        val elapsedSec = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1000L).toInt()
        val baseData = mutableMapOf<String, Any>(
            "avdName" to resolvedAvd,
            "started" to true,
            "waitedSec" to elapsedSec,
        )
        if (discoveredSerial != null) {
            baseData["emulatorSerial"] = discoveredSerial
            return McpToolResult(
                status = McpToolStatus.OK,
                message = "start_emulator executed successfully. Started '$resolvedAvd' as $discoveredSerial.",
                data = baseData,
                artifacts = emptyList(),
                errorCode = null,
            )
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "start_emulator executed successfully. Started '$resolvedAvd', but no new online emulator detected within ${resolvedWaitSec}s.",
            data = baseData,
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
