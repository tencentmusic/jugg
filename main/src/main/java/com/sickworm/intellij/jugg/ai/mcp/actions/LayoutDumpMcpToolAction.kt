package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult

/**
 * LayoutDumpMcpToolAction exposes the `layout-dump` MCP tool.
 * Core logic lives in LayoutDumpHelper and is shared with FigmaLayoutVerifyMcpToolAction.
 */
class LayoutDumpMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.LAYOUT_DUMP

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Dump UI hierarchy from app-side ViewHierarchy server to a local HTML artifact. " +
            "Returns data.file for the HTML file. Supports rootLayout subtree dump and includeGone.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "rootLayout" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional node id to dump only that subtree. Pass the `id` value from previous layout_dump (prefer short id, e.g. \"content\").",
                ),
                "includeGone" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, include GONE nodes in the output for diagnostics. Default is false.",
                ),
                "allWindows" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, dump all windows instead of only the top window. Default is false.",
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val rootLayout = arguments["rootLayout"] as? String
        // Accept both new ("includeGone") and legacy ("isIncludeGone") param names.
        val includeGone = (arguments["includeGone"] ?: arguments["isIncludeGone"]) as? Boolean ?: false
        val allWindows = (arguments["allWindows"] ?: arguments["isAllWindows"]) as? Boolean ?: false
        return LayoutDumpHelper.dump(runtime, toolName, rootLayout, includeGone, allWindows)
    }
}
