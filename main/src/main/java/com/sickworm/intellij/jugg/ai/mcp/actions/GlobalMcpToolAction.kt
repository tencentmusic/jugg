package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry

/**
 * GlobalMcpToolAction marks tools that can execute without a project-scoped runtime
 * (i.e. those listed in [McpToolActionRegistry.noProjectDirTools]).
 *
 * Implementors must not access any runtime-provided resource inside [executeGlobal].
 */
interface GlobalMcpToolAction {
    fun executeGlobal(toolRegistry: McpToolRegistry): McpToolResult
}
