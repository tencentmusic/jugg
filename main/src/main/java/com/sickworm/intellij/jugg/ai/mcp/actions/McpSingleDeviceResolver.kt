package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.ai.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.deploy.IDeviceAdb

/** Carries either the single online adb target or a structured device-selection failure. */
internal sealed class McpSingleDeviceResult {
    data class Selected(val adb: IDeviceAdb) : McpSingleDeviceResult()
    data class Failure(val result: McpToolResult) : McpSingleDeviceResult()
}

/** Resolves one device for MCP operations that cannot safely target multiple devices. */
internal fun resolveMcpSingleDevice(
    runtime: IMcpRuntime,
    toolName: String,
    targetDeviceSerial: String?,
): McpSingleDeviceResult {
    val selection = DeviceSelectionResolver().resolve(runtime.deployTargetManager, targetDeviceSerial)
    val failure = when (selection) {
        is DeviceSelectionResult.NoDevice -> selection.messageDetail to McpErrorCode.NO_DEVICE
        is DeviceSelectionResult.MultipleDevices -> selection.messageDetail to McpErrorCode.MULTIPLE_DEVICE
        is DeviceSelectionResult.Selected -> null
    }
    if (failure != null) {
        return McpSingleDeviceResult.Failure(deviceSelectionError(toolName, failure.first, failure.second))
    }
    val device = (selection as DeviceSelectionResult.Selected).device
    val adb = runtime.deployTargetManager.createDeviceAdb(device).takeIf { it.isOnline }
        ?: return McpSingleDeviceResult.Failure(
            deviceSelectionError(toolName, "No connected device is available.", McpErrorCode.NO_DEVICE),
        )
    return McpSingleDeviceResult.Selected(adb)
}

private fun deviceSelectionError(toolName: String, reason: String, errorCode: String): McpToolResult {
    return McpToolResult(
        status = McpToolStatus.ERROR,
        message = "$toolName failed. Reason: $reason",
        data = emptyMap<String, Any>(),
        artifacts = emptyList(),
        errorCode = errorCode,
    )
}
