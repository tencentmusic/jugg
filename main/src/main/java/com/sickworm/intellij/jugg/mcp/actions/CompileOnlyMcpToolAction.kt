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
        description = "Compile modified sources with Jugg incremental build without deployment. Use when: you only need compile validation or no device is connected. Avoid: when runtime/device behavior must be verified. Side effects: build only; no deploy and no app restart.",
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
