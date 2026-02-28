package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * CrashReportMcpToolAction implements MCP tool `crash_report` and summarizes latest crash signals from target device logs.
 * Data Contract: Returns structured crash summary and always emits full error log artifact on successful execution.
 */
class CrashReportMcpToolAction : McpToolAction {
    override val toolName: String = "crash_report"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Collect latest app crash summary and related logs from target device.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "packageName" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional package name. If absent, uses current Jugg package name.",
                    pattern = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                    examples = listOf("com.example.app"),
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
                        "isProcessAlive" to McpJsonSchemaProperty(type = "boolean"),
                        "hasCrash" to McpJsonSchemaProperty(type = "boolean"),
                        "crashLogs" to McpJsonSchemaProperty(
                            type = "array",
                            items = McpJsonSchemaProperty(type = "string"),
                        ),
                        "relatedActivity" to McpJsonSchemaProperty(type = "string"),
                        "packageName" to McpJsonSchemaProperty(type = "string"),
                        "allErrorLogPath" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.log$"),
                    ),
                    required = listOf("isProcessAlive", "hasCrash", "crashLogs", "allErrorLogPath"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return crashReportAction(runtime, arguments["packageName"] as? String)
    }

    /**
     * Build a crash triage payload from current device and recent error logs.
     */
    private fun crashReportAction(runtime: IMcpRuntime, packageName: String?): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult(toolName)
        val resolvedPackageName = packageName ?: runtime.deployTargetManager.getPackageNameOrNull()

        return try {
            val allErrorLogs = runtime.deployTargetManager.dumpErrorLogs()
            val toolDir = ensureToolDir(runtime, toolName)
                ?: return McpToolResult.internalErrorResult(toolName, "failed to prepare artifact directory")
            val allErrorLogFile = File(toolDir, "crash_report_${System.currentTimeMillis()}.log")
            allErrorLogFile.writeText(allErrorLogs)

            val crashSnippet = extractLatestCrashSnippet(allErrorLogs)
            val relatedActivity = findRelatedActivity(selected.adb)
            val isProcessAlive = isProcessAlive(selected.adb, resolvedPackageName)

            val data = mutableMapOf<String, Any>(
                "isProcessAlive" to isProcessAlive,
                "hasCrash" to crashSnippet.isNotEmpty(),
                "crashLogs" to crashSnippet,
                "allErrorLogPath" to allErrorLogFile.absolutePath,
            )
            if (!relatedActivity.isNullOrBlank()) {
                data["relatedActivity"] = relatedActivity
            }
            if (!resolvedPackageName.isNullOrBlank()) {
                data["packageName"] = resolvedPackageName
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = "crash_report executed successfully.",
                data = data,
                artifacts = listOf(McpArtifact(type = "log", path = allErrorLogFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
        }
    }

    private fun extractLatestCrashSnippet(logs: String): List<String> {
        if (logs.isBlank()) {
            return emptyList()
        }
        val lines = logs.lines()
        val markerIndex = lines.indexOfLast {
            it.contains("FATAL EXCEPTION") ||
                it.contains("AndroidRuntime") ||
                it.contains("Process:") ||
                it.contains(" has died")
        }
        if (markerIndex < 0) {
            return emptyList()
        }

        val selected = mutableListOf<String>()
        val endIndex = minOf(lines.lastIndex, markerIndex + 120)
        for (i in markerIndex..endIndex) {
            val line = lines[i]
            if (i > markerIndex && line.startsWith("--------- beginning")) {
                break
            }
            selected += line
            if (selected.size >= 80) {
                break
            }
        }

        return selected
            .takeLast(30)
            .map { it.take(800) }
            .filter { it.isNotBlank() }
    }

    private fun findRelatedActivity(adb: IDeviceAdb): String? {
        val dumpOutput = adb.execAdbShellCmd("dumpsys activity activities")
        if (dumpOutput.isBlank()) {
            return null
        }
        val componentRegex = Regex("([a-zA-Z][a-zA-Z0-9_.$]*(?:\\.[a-zA-Z0-9_.$]+)+/[a-zA-Z0-9_.$]+)")
        val priorityKeywords = listOf("topResumedActivity", "mResumedActivity", "mFocusedActivity")
        priorityKeywords.forEach { keyword ->
            val line = dumpOutput.lineSequence().firstOrNull { it.contains(keyword) }
            if (line != null) {
                val activity = componentRegex.find(line)?.groupValues?.getOrNull(1)
                if (!activity.isNullOrBlank()) {
                    return activity
                }
            }
        }
        return null
    }

    private fun isProcessAlive(adb: IDeviceAdb, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) {
            return false
        }
        val pidOfOutput = adb.execAdbShellCmd("pidof $packageName")
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        if (pidOfOutput.isNotBlank()) {
            return true
        }
        val psOutput = adb.execAdbShellCmd("ps | grep $packageName")
        return psOutput.lineSequence().any { it.contains(packageName) }
    }

    /**
     * SelectedAdb carries adb and messageDetail.
     */
    private data class SelectedAdb(
        val adb: IDeviceAdb,
    )

    private fun resolveOnlineDevice(runtime: IMcpRuntime): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb)
    }

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, toolName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun noDeviceResult(toolName: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }
}
