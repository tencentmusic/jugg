package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult

interface McpToolAction {
    val toolName: String
    val definition: McpToolDefinition

    fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult
}

