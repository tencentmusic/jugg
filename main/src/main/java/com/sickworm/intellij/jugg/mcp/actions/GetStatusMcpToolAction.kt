package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.project.ChangedFile

private const val MAX_FILE_PATHS = 20

/**
 * GetStatusMcpToolAction implements MCP tool `status` and returns current deploy state,
 * uncompiled file counts by type, and up to 20 absolute file paths.
 */
class GetStatusMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.GET_STATUS

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Return current Jugg deploy state, uncompiled file counts by type, " +
            "and absolute paths of modified files (at most $MAX_FILE_PATHS entries).",
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
                        "hasDevice" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when a device is connected and ready (state is not NOTHING_CAN_DO).",
                        ),
                        "needFallback" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when a full Gradle build is required (state is READY_FULL_COMPILE).",
                        ),
                        "stateMessage" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Human-readable reason for current state.",
                        ),
                        "fileCounts" to McpJsonSchemaProperty(
                            type = "object",
                            description = "total and per-type counts of uncompiled files.",
                            additionalProperties = true,
                        ),
                        "files" to McpJsonSchemaProperty(
                            type = "array",
                            description = "Absolute paths of uncompiled files (at most $MAX_FILE_PATHS).",
                            items = McpJsonSchemaProperty(type = "string"),
                        ),
                        "detail" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Empty when all files are listed. Natural-language note when the list is truncated, " +
                                "e.g. \"Showing 20 of 25 files. 5 more files are not listed.\"",
                        ),
                    ),
                    required = listOf("hasDevice", "needFallback", "stateMessage", "fileCounts", "files", "detail"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val deployState = runtime.deployStateManager?.updateDeployState()
            ?: return McpToolResult.internalErrorResult(toolName, "deploy state manager is unavailable")

        val deployFileManager = runtime.deployFileManager
            ?: return McpToolResult.internalErrorResult(toolName, "deploy file manager is unavailable")

        val fallbackReason: String? = runtime.incrementalCompileFallbackChecker?.checkFallback()
        val needFallback = fallbackReason != null || deployState.state == JuggDeployState.State.READY_FULL_COMPILE

        val uncompiledFiles: List<ChangedFile> = deployFileManager.getUncompiledFiles()

        val countsByType = uncompiledFiles
            .groupingBy { it.type.name }
            .eachCount()

        val fileCounts: Map<String, Any> = mutableMapOf<String, Any>("total" to uncompiledFiles.size)
            .also { it.putAll(countsByType) }

        val total = uncompiledFiles.size
        val files: List<String> = uncompiledFiles
            .take(MAX_FILE_PATHS)
            .map { it.file.absolutePath }
        val truncationNote: String = if (total > MAX_FILE_PATHS) {
            "Showing $MAX_FILE_PATHS of $total files. ${total - MAX_FILE_PATHS} more files are not listed."
        } else {
            ""
        }
        val detail: String = when {
            fallbackReason != null && truncationNote.isNotEmpty() -> "$fallbackReason\n$truncationNote"
            fallbackReason != null -> fallbackReason
            else -> truncationNote
        }

        val data: Map<String, Any> = mapOf(
            "hasDevice" to (runtime.deployTargetManager.hasDevice),
            "needFallback" to needFallback,
            "stateMessage" to deployState.msg,
            "fileCounts" to fileCounts,
            "files" to files,
            "detail" to detail,
        )

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "status executed successfully.",
            data = data,
            artifacts = emptyList(),
            errorCode = null,
        )
    }
}
