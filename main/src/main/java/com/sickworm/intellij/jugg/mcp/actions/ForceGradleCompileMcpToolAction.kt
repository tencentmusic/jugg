package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

/**
 * ForceGradleCompileMcpToolAction implements MCP tool `force_gradle_compile` and converts request arguments into tool execution and MCP result payloads.
 */
class ForceGradleCompileMcpToolAction : McpToolAction {
    override val toolName: String = "force_gradle_compile"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Compile via Gradle fallback instead of Jugg incremental build. Use when Jugg compile repeatedly fails or behaves unexpectedly. Avoid as default path for routine iterations. Side effects: slower full Gradle-oriented compile path.",
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
                        "status" to McpJsonSchemaProperty(type = "string", `enum` = listOf("running", "success", "failed")),
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
            val trigger = GradleCompileJobManager.trigger(runtime)
            McpToolResult(
                status = McpToolStatus.OK,
                message = trigger.message,
                data = mapOf(
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
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("force_gradle_compile", e.message ?: "unknown error")
        }
    }
}
