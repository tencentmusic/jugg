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
 * CrashReportMcpToolAction implements MCP tool `crash-report` and summarizes latest target-process crash signals.
 * Strategy: prefer `logcat -b crash`, then fallback to `-b main` only when no crash signal is found.
 */
class CrashReportMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.CRASH_REPORT

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Collect latest app crash summary and related logs from target device.",
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
                        "isProcessAlive" to McpJsonSchemaProperty(type = "boolean"),
                        "hasCrash" to McpJsonSchemaProperty(type = "boolean"),
                        "crashLogs" to McpJsonSchemaProperty(
                            type = "array",
                            items = McpJsonSchemaProperty(type = "string"),
                        ),
                        "reason" to McpJsonSchemaProperty(type = "string"),
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
        return crashReportAction(runtime)
    }

    /**
     * Build crash triage payload and keep full raw log artifact for deep investigation.
     */
    private fun crashReportAction(runtime: IMcpRuntime): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult(toolName)
        val packageName = runtime.deployTargetManager.getPackageNameOrNull()
        val processInfo = collectProcessInfo(selected.adb, packageName)
        val crashBufferRaw = safeExecAdb(selected.adb, LOGCAT_CRASH_COMMAND)
        val crashBufferFiltered = filterTargetLines(crashBufferRaw, packageName, processInfo)
        var crashSnippet = extractLatestCrashSnippet(crashBufferFiltered)

        val shouldCollectMain = crashSnippet.isEmpty()
        val mainBufferRaw = if (shouldCollectMain) safeExecAdb(selected.adb, LOGCAT_MAIN_COMMAND) else ""
        val mainBufferFiltered = if (shouldCollectMain) {
            filterTargetLines(mainBufferRaw, packageName, processInfo)
        } else {
            emptyList()
        }
        if (crashSnippet.isEmpty()) {
            crashSnippet = extractLatestCrashSnippet(mainBufferFiltered)
        }

        return try {
            val toolDir = ensureToolDir(runtime, toolName)
                ?: return McpToolResult.internalErrorResult(toolName, "failed to prepare artifact directory")
            val allErrorLogFile = File(toolDir, "crash_report_${System.currentTimeMillis()}.log")
            allErrorLogFile.writeText(
                buildRawLogArtifactContent(
                    packageName = packageName,
                    processInfo = processInfo,
                    crashBufferRaw = crashBufferRaw,
                    mainBufferRaw = mainBufferRaw,
                    mainCollected = shouldCollectMain,
                )
            )

            val hasCrash = crashSnippet.isNotEmpty()
            val reason = if (hasCrash) {
                null
            } else {
                buildNoCrashReason(packageName, processInfo.isAlive)
            }
            val relatedActivity = findRelatedActivity(selected.adb)
            val data = mutableMapOf<String, Any>(
                "isProcessAlive" to processInfo.isAlive,
                "hasCrash" to hasCrash,
                "crashLogs" to crashSnippet,
                "allErrorLogPath" to allErrorLogFile.absolutePath,
            )
            if (!reason.isNullOrBlank()) {
                data["reason"] = reason
            }
            if (!relatedActivity.isNullOrBlank()) {
                data["relatedActivity"] = relatedActivity
            }
            if (!packageName.isNullOrBlank()) {
                data["packageName"] = packageName
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = buildMessage(hasCrash, reason),
                data = data,
                artifacts = listOf(McpArtifact(type = "log", path = allErrorLogFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
        }
    }

    private fun collectProcessInfo(adb: IDeviceAdb, packageName: String?): ProcessInfo {
        if (packageName.isNullOrBlank()) {
            return ProcessInfo(emptySet(), emptySet(), isAlive = false)
        }
        val pidOutput = safeExecAdb(adb, "pidof $packageName")
        val pidSet = parsePidSet(pidOutput).toMutableSet()

        val processNames = mutableSetOf<String>()
        val psOutput = safeExecAdb(adb, "ps -A | grep $packageName")
        psOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(packageName) }
            .forEach { line ->
                val parts = line.split(WHITESPACE_REGEX)
                val pid = parts.getOrNull(1)?.toIntOrNull()
                if (pid != null) {
                    pidSet += pid
                }
                val processName = parts.lastOrNull().orEmpty()
                if (processName.isNotBlank()) {
                    processNames += processName
                }
            }

        return ProcessInfo(
            pids = pidSet,
            processNames = processNames,
            isAlive = pidSet.isNotEmpty() || processNames.isNotEmpty(),
        )
    }

    private fun parsePidSet(raw: String): Set<Int> {
        return PID_REGEX.findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .toSet()
    }

    private fun safeExecAdb(adb: IDeviceAdb, cmd: String): String {
        return runCatching { adb.execAdbShellCmd(cmd) }.getOrDefault("")
    }

    private fun filterTargetLines(rawLogs: String, packageName: String?, processInfo: ProcessInfo): List<String> {
        if (rawLogs.isBlank()) {
            return emptyList()
        }
        if (packageName.isNullOrBlank()) {
            return rawLogs.lineSequence()
                .map { it.take(MAX_LINE_LENGTH) }
                .filter { it.isNotBlank() }
                .toList()
        }
        val targetPidSet = processInfo.pids
        val targetProcessNames = processInfo.processNames
        return rawLogs.lineSequence()
            .map { it.take(MAX_LINE_LENGTH) }
            .filter { line ->
                line.contains(packageName) ||
                    targetProcessNames.any { processName -> processName.isNotBlank() && line.contains(processName) } ||
                    parsePidFromThreadtime(line)?.let { targetPidSet.contains(it) } == true
            }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parsePidFromThreadtime(line: String): Int? {
        val match = THREADTIME_PID_REGEX.find(line) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun extractLatestCrashSnippet(lines: List<String>): List<String> {
        if (lines.isEmpty()) {
            return emptyList()
        }
        val strongMarkerIndex = lines.indexOfLast { line ->
            STRONG_CRASH_MARKERS.any { marker -> line.contains(marker) }
        }
        val markerIndex = if (strongMarkerIndex >= 0) {
            strongMarkerIndex
        } else {
            lines.indexOfLast { line ->
                CRASH_MARKERS.any { marker -> line.contains(marker) }
            }
        }
        if (markerIndex < 0) {
            return emptyList()
        }

        val selected = mutableListOf<String>()
        val endIndex = minOf(lines.lastIndex, markerIndex + 120)
        for (index in markerIndex..endIndex) {
            val line = lines[index]
            if (index > markerIndex && line.startsWith("--------- beginning")) {
                break
            }
            selected += line
            if (selected.size >= 80) {
                break
            }
        }
        return selected.takeLast(30)
    }

    private fun buildNoCrashReason(packageName: String?, isProcessAlive: Boolean): String {
        if (packageName.isNullOrBlank()) {
            return "No crash signal found. Target package name is unavailable."
        }
        if (!isProcessAlive) {
            return "No crash signal found. Target process is not running."
        }
        return "No crash signal found for target process in crash/main buffers."
    }

    private fun buildMessage(hasCrash: Boolean, reason: String?): String {
        if (hasCrash) {
            return "crash_report detected crash signal from target process."
        }
        return "crash_report found no crash signal. Reason: ${reason.orEmpty()}"
    }

    private fun buildRawLogArtifactContent(
        packageName: String?,
        processInfo: ProcessInfo,
        crashBufferRaw: String,
        mainBufferRaw: String,
        mainCollected: Boolean,
    ): String {
        val header = buildString {
            appendLine("=== crash-report metadata ===")
            appendLine("packageName=${packageName.orEmpty()}")
            appendLine("isProcessAlive=${processInfo.isAlive}")
            appendLine("pids=${processInfo.pids.sorted().joinToString(",")}")
            appendLine("processNames=${processInfo.processNames.sorted().joinToString(",")}")
            appendLine("mainBufferCollected=$mainCollected")
            appendLine()
        }
        return buildString {
            append(header)
            appendLine("=== raw logcat crash buffer ===")
            appendLine(crashBufferRaw)
            if (mainCollected) {
                appendLine()
                appendLine("=== raw logcat main buffer ===")
                appendLine(mainBufferRaw)
            }
        }
    }

    private fun findRelatedActivity(adb: IDeviceAdb): String? {
        val dumpOutput = safeExecAdb(adb, "dumpsys activity activities")
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

    /**
     * SelectedAdb carries adb and messageDetail.
     */
    private data class SelectedAdb(
        val adb: IDeviceAdb,
    )

    private data class ProcessInfo(
        val pids: Set<Int>,
        val processNames: Set<String>,
        val isAlive: Boolean,
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
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }

    companion object {
        private const val LOGCAT_CRASH_COMMAND = "logcat -d -b crash -v threadtime"
        private const val LOGCAT_MAIN_COMMAND = "logcat -d -b main -v threadtime"
        private const val MAX_LINE_LENGTH = 800
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PID_REGEX = Regex("\\d+")
        private val THREADTIME_PID_REGEX = Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(\\d+)\\s+\\d+\\s+[VDIWEAF]\\s+.+$")
        private val CRASH_MARKERS = listOf(
            "FATAL EXCEPTION",
            "Fatal signal",
            "Process:",
            "AndroidRuntime",
            "backtrace",
        )
        private val STRONG_CRASH_MARKERS = listOf(
            "FATAL EXCEPTION",
            "Fatal signal",
        )
    }
}
