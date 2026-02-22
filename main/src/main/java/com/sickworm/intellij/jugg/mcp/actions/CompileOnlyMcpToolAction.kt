package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult

/**
 * CompileOnlyMcpToolAction implements MCP tool `compile_only` and converts request arguments into tool execution and MCP result payloads.
 */
class CompileOnlyMcpToolAction : McpToolAction {
    override val toolName: String = "compile_only"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Compile modified source files with Jugg incremental build without deploying to device. Use when you want to validate that code compiles successfully, or when no device is connected. Avoid when changes must take effect on device. Side effects: build only, no deploy and no app restart.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return compileAction(runtime)
    }

    private fun compileAction(runtime: IMcpRuntime): McpToolResult {
        return CompileAndDeployMcpToolAction.deployAction(runtime, "compile_only", isSkipDeploy = true)
    }

}
