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
                        "triggered" to McpJsonSchemaProperty(type = "boolean"),
                    ),
                    required = listOf("triggered"),
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
            runtime.forceGradleCompileHelper.executeGradleCompile(autoConfirm = true)
            McpToolResult(
                status = McpToolStatus.OK,
                message = "force_gradle_compile executed successfully.",
                data = mapOf("triggered" to true),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("force_gradle_compile", e.message ?: "unknown error")
        }
    }
}
