package com.sickworm.intellij.jugg.mcp

interface IMcpRuntime {
    fun restartApp(serial: String?): McpToolResult
    fun compile(): McpToolResult
    fun deploy(): McpToolResult
    fun cleanReinstall(): McpToolResult
    fun deviceList(): McpToolResult
    fun screenshot(serial: String?): McpToolResult
    fun record(serial: String?, durationSec: Int?): McpToolResult
    fun layoutDump(serial: String?): McpToolResult
}

object McpRuntimeHolder {
    @Volatile
    var runtime: IMcpRuntime? = null
}
