package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult

/**
 * CompileOnlyMcpToolAction implements MCP tool `compile` and converts request arguments into tool execution and MCP result payloads.
 */
class CompileOnlyMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.COMPILE

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Compile modified sources with Jugg incremental build without deployment.",
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
        return CompileAndDeployMcpToolAction.deployAction(runtime, "compile", isSkipDeploy = true)
    }

}
