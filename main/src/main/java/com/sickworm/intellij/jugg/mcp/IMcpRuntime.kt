package com.sickworm.intellij.jugg.mcp

interface IMcpRuntime {
    fun restartApp(serial: String?): McpToolResult
    fun emulatorList(): McpToolResult
    fun startEmulator(avdName: String?, waitForDeviceSec: Int?): McpToolResult
    fun compile(): McpToolResult
    fun deploy(): McpToolResult
    fun cleanReinstall(): McpToolResult
    fun forceGradleCompile(): McpToolResult
    fun deviceList(): McpToolResult
    fun screenshot(serial: String?): McpToolResult
    fun record(
        serial: String?,
        durationSec: Int?,
        packageName: String?,
        activity: String?,
        tapX: Int?,
        tapY: Int?,
        preTapDelaySec: Double?,
        tapRepeat: Int?,
        tapIntervalSec: Double?,
        recordStartDelaySec: Double?,
    ): McpToolResult
    fun layoutDump(serial: String?): McpToolResult
    fun appStart(serial: String?, packageName: String?, activity: String?): McpToolResult
    fun tap(serial: String?, x: Int?, y: Int?): McpToolResult
}

object McpRuntimeHolder {
    @Volatile
    var runtime: IMcpRuntime? = null
}
