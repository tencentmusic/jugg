package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

/**
 * ForceGradleCompileMcpToolAction implements MCP tool `gradle-build` and converts request arguments into tool execution and MCP result payloads.
 */
class ForceGradleCompileMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.GRADLE_BUILD

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Compile via Gradle fallback instead of Jugg incremental path.",
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
                        "accepted" to McpJsonSchemaProperty(type = "boolean"),
                        "jobId" to McpJsonSchemaProperty(type = "string"),
                        "executionType" to McpJsonSchemaProperty(type = "string", `enum` = listOf("local", "remote")),
                        "logPath" to McpJsonSchemaProperty(type = "string"),
                        "isFinal" to McpJsonSchemaProperty(type = "boolean"),
                        "status" to McpJsonSchemaProperty(type = "string", `enum` = listOf("running", "success", "failed", "canceled")),
                        "triggered" to McpJsonSchemaProperty(type = "boolean"),
                    ),
                    required = listOf("accepted", "jobId", "executionType", "logPath", "isFinal", "status", "triggered"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return forceGradleCompileAction(runtime)
    }

    private fun forceGradleCompileAction(runtime: IMcpRuntime): McpToolResult {
        return try {
            val trigger = CompileJobManager.triggerForceGradleCompile(runtime)
            val isFinalSuccess = trigger.isFinal && trigger.status == "success"
            val isStillRunning = !trigger.isFinal
            val status = if (isFinalSuccess || isStillRunning) McpToolStatus.OK else McpToolStatus.ERROR
            val errorCode = if (status == McpToolStatus.ERROR) McpErrorCode.INTERNAL_ERROR else null

            McpToolResult(
                status = status,
                message = trigger.message,
                data = mutableMapOf<String, Any>(
                    "accepted" to trigger.accepted,
                    "jobId" to trigger.jobId,
                    "executionType" to trigger.executionType,
                    "logPath" to trigger.logPath,
                    "isFinal" to trigger.isFinal,
                    "status" to trigger.status,
                    // keep old field for compatibility with old callers.
                    "triggered" to trigger.accepted,
                ),
                artifacts = emptyList(),
                errorCode = errorCode,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("gradle-build", e.message ?: "unknown error")
        }
    }
}
