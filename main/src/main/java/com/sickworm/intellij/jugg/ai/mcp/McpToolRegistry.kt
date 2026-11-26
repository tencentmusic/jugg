package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolAction
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry

/**
 * McpToolRegistry registers and looks up mcp tool handlers.
 */
class McpToolRegistry(
    private val actionRegistry: McpToolActionRegistry = McpToolActionRegistry(),
    capabilities: List<String> = actionRegistry.listActions().map { it.toolName },
) {
    private val capabilities = capabilities.distinct().filter(actionRegistry::hasAction)

    fun listTools(): List<McpToolDefinition> {
        return capabilities.mapNotNull(actionRegistry::getAction).map { it.definition }
    }

    fun hasTool(toolName: String): Boolean {
        return toolName in capabilities
    }

    fun getToolDefinition(toolName: String): McpToolDefinition? {
        return getAction(toolName)?.definition
    }

    fun getAction(toolName: String): McpToolAction? {
        return if (hasTool(toolName)) actionRegistry.getAction(toolName) else null
    }

    fun listCapabilities(): List<String> {
        return capabilities
    }
}
