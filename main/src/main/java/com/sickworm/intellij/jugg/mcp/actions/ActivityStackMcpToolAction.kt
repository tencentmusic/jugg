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
 * ActivityStackMcpToolAction implements MCP tool `activity_stack` and converts request arguments into tool execution and MCP result payloads.
 */
class ActivityStackMcpToolAction : McpToolAction {
    override val toolName: String = "activity_stack"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Return current Activity stack from target device.",
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
                        "topActivity" to McpJsonSchemaProperty(type = "string"),
                        "activities" to McpJsonSchemaProperty(
                            type = "array",
                            items = McpJsonSchemaProperty(type = "string")
                        ),
                        "dumpFile" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.txt$"),
                        "sourceCommand" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = listOf("activities", "dumpFile", "sourceCommand"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return activityStackAction(runtime)
    }

    private fun activityStackAction(runtime: IMcpRuntime): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("activity_stack")
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            return preWaitResult.errorResult ?: McpToolResult.internalErrorResult("activity_stack", "app is not ready")
        }
        val adb = selected.adb
        val sourceCommand = "dumpsys activity activities"

        return McpAppReadyGuard.executeWithRetryIfPreWaited(preWaitResult) {
            try {
                val dumpOutput = adb.execAdbShellCmd(sourceCommand)
                if (dumpOutput.isBlank()) {
                    return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult("activity_stack", "empty dumpsys output")
                }

                val toolDir = ensureToolDir(runtime, "activity_stack")
                    ?: return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult(
                        "activity_stack",
                        "failed to prepare artifact directory"
                    )
                val dumpFile = File(toolDir, "activity_stack_${System.currentTimeMillis()}.txt")
                dumpFile.writeText(dumpOutput)

                val parsedEntries = parseActivityEntries(dumpOutput)
                val topContext = findTopContext(dumpOutput, parsedEntries)
                val activities = buildActivitiesTopToBottom(topContext, parsedEntries)

                val data = mutableMapOf<String, Any>(
                    "activities" to activities,
                    "dumpFile" to dumpFile.absolutePath,
                    "sourceCommand" to sourceCommand,
                )
                if (!topContext.activity.isNullOrBlank()) {
                    data["topActivity"] = topContext.activity
                }

                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "activity_stack executed successfully.",
                    data = data,
                    artifacts = listOf(McpArtifact(type = "text", path = dumpFile.absolutePath)),
                    errorCode = null,
                )
            } catch (e: Exception) {
                McpToolResult.internalErrorResult("activity_stack", e.message ?: "unknown error")
            }
        }
    }

    /**
     * ActivityEntry carries stackIndex, histIndex, taskId, and component.
     */
    internal data class ActivityEntry(
        val stackIndex: Int,
        val histIndex: Int,
        val taskId: Int,
        val component: String,
        val line: String,
    )

    /**
     * TopContext carries activity and taskId.
     */
    private data class TopContext(
        val activity: String?,
        val taskId: Int?,
    )

    private fun findTopContext(dumpOutput: String, parsedEntries: List<ActivityEntry>): TopContext {
        val componentRegex = Regex("([a-zA-Z][a-zA-Z0-9_.$]*(?:\\.[a-zA-Z0-9_.$]+)+/[a-zA-Z0-9_.$]+)")
        val inlineTaskRegex = Regex("\\bt(\\d+)\\b")
        val priorityKeywords = listOf("topResumedActivity", "mResumedActivity", "mFocusedActivity")
        for (keyword in priorityKeywords) {
            val line = dumpOutput.lineSequence().firstOrNull { it.contains(keyword) }
            val activity = line?.let { componentRegex.find(it)?.groupValues?.getOrNull(1) }
            if (!activity.isNullOrBlank()) {
                val taskId = line.let { inlineTaskRegex.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    ?: parsedEntries.firstOrNull { it.component == activity && it.taskId > 0 }?.taskId
                return TopContext(activity = activity, taskId = taskId)
            }
        }

        val fallbackTop = parsedEntries.firstOrNull()
        return TopContext(activity = fallbackTop?.component, taskId = fallbackTop?.taskId?.takeIf { it > 0 })
    }

    private fun buildActivitiesTopToBottom(topContext: TopContext, parsedEntries: List<ActivityEntry>): List<String> {
        val candidates = if (topContext.taskId != null && topContext.taskId > 0) {
            parsedEntries.filter { it.taskId == topContext.taskId }
        } else {
            parsedEntries
        }

        val ordered = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        if (!topContext.activity.isNullOrBlank() && seen.add(topContext.activity)) {
            ordered += topContext.activity
        }

        candidates.forEach { entry ->
            if (seen.add(entry.component)) {
                ordered += entry.component
            }
        }
        return ordered
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

    companion object {
        internal fun parseActivityEntries(dumpOutput: String): List<ActivityEntry> {
            var currentTaskId = -1
            val entries = mutableListOf<ActivityEntry>()
            dumpOutput.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                TASK_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { currentTaskId = it }
                val shouldParse = line.contains("ActivityRecord{") ||
                    line.contains("Hist #") ||
                    line.contains("topResumedActivity") ||
                    line.contains("mResumedActivity") ||
                    line.contains("mFocusedActivity")
                if (!shouldParse) {
                    return@forEachIndexed
                }

                val histIndex = HIST_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                val inlineTaskId = INLINE_TASK_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val resolvedTaskId = inlineTaskId ?: currentTaskId
                COMPONENT_REGEX.findAll(line).forEach { componentMatch ->
                    entries += ActivityEntry(
                        stackIndex = index,
                        histIndex = histIndex,
                        taskId = resolvedTaskId,
                        component = componentMatch.groupValues[1],
                        line = line.take(600),
                    )
                }
            }
            return entries
        }

        private val TASK_REGEX = Regex("Task\\{[^#]*#(\\d+)")
        private val HIST_REGEX = Regex("Hist\\s+#(\\d+)")
        private val INLINE_TASK_REGEX = Regex("\\bt(\\d+)\\b")
        private val COMPONENT_REGEX = Regex("([a-zA-Z][a-zA-Z0-9_.$]*(?:\\.[a-zA-Z0-9_.$]+)+/[a-zA-Z0-9_.$]+)")
    }
}
