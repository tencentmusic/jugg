package com.sickworm.intellij.jugg.mcp

interface IMcpRuntime {
    fun restartApp(serial: String?): McpToolResult
}

object McpRuntimeHolder {
    @Volatile
    var runtime: IMcpRuntime? = null
}
