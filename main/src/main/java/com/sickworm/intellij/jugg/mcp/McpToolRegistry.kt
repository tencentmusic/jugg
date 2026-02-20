package com.sickworm.intellij.jugg.mcp

import com.sickworm.intellij.jugg.mcp.actions.McpToolAction
import com.sickworm.intellij.jugg.mcp.actions.McpToolActionRegistry

/**
 * McpToolRegistry registers and looks up mcp tool handlers.
 */
class McpToolRegistry(
    private val actionRegistry: McpToolActionRegistry = McpToolActionRegistry(),
) {

    fun listTools(): List<McpToolDefinition> {
        return actionRegistry.listActions().map { it.definition }
    }

    fun hasTool(toolName: String): Boolean {
        return actionRegistry.hasAction(toolName)
    }

    fun getToolDefinition(toolName: String): McpToolDefinition? {
        return getAction(toolName)?.definition
    }

    fun getAction(toolName: String): McpToolAction? {
        return actionRegistry.getAction(toolName)
    }
}

