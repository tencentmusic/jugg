package com.sickworm.intellij.jugg.mcp

interface IMcpRuntime {
    fun restartApp(serial: String?): McpToolResult
    fun compile(): McpToolResult
    fun deploy(): McpToolResult
    fun cleanReinstall(): McpToolResult
}

object McpRuntimeHolder {
    @Volatile
    var runtime: IMcpRuntime? = null
}
