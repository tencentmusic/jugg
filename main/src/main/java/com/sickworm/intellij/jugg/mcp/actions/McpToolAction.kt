package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult

/**
 * McpToolAction describes one MCP tool action, including metadata and execution entrypoint.
 */
interface McpToolAction {
    val toolName: String
    val definition: McpToolDefinition

    fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult
}
