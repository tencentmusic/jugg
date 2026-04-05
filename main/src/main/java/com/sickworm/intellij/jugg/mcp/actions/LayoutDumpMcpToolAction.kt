package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult

/**
 * LayoutDumpMcpToolAction exposes the `layout_dump` MCP tool.
 * Core logic lives in LayoutDumpHelper and is shared with FigmaLayoutVerifyMcpToolAction.
 */
class LayoutDumpMcpToolAction : McpToolAction {
    override val toolName: String = "layout_dump"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Dump UI hierarchy from app-side ViewHierarchy server to a local JSON artifact. " +
            "Returns data.file and optional inline data.content. Supports rootLayout subtree dump and isIncludeGone.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "rootLayout" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional node id to dump only that subtree. Pass the `id` value from previous layout_dump (prefer short id, e.g. \"content\").",
                ),
                "isIncludeGone" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, include GONE nodes in the output for diagnostics. Default is false.",
                ),
                "isAllWindows" to McpJsonSchemaProperty(
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
        val isIncludeGone = arguments["isIncludeGone"] as? Boolean ?: false
        val isAllWindows = arguments["isAllWindows"] as? Boolean ?: false
        return LayoutDumpHelper.dump(runtime, toolName, rootLayout, isIncludeGone, isAllWindows)
    }
}
