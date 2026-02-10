package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpDeviceInfo
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

class RestartAppMcpToolAction : McpToolAction {
    override val toolName: String = "restart_app"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Restart app on target device. Use when app process must be refreshed after deploy or runtime changes. Avoid when installation artifacts must be replaced. Side effects: restarts app process, no reinstall.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "serial" to McpToolSchemas.serialProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return restartAppAction(runtime, arguments["serial"] as? String)
    }

    private fun restartAppAction(runtime: IMcpRuntime, serial: String?): McpToolResult {
        val targetDevice = runtime.deployTargetManager.getConnectedDevices().find { it.serialNumber == serial }
        if (targetDevice == null && !serial.isNullOrEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: No device found for serial: $serial.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_NO_DEVICE,
            )
        }
        val targetDevices = if (targetDevice == null) {
            runtime.deployTargetManager.getSelectedDevices()
        } else {
            listOf(targetDevice)
        }
        if (targetDevices.isEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: No connected devices.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_NO_DEVICE,
            )
        }

        var isSuccess = true
        targetDevices.forEach { device ->
            val result = runtime.deployTargetManager.restartApp(device)
            isSuccess = isSuccess && result
        }
        if (!isSuccess) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: Failed to restart app on some devices. Please check log in \\\$PROJECT_DIR/build/jugg/log/compile_latest.log\"",
                data = mapOf(
                    "devices" to targetDevices.map { it.mcpDeviceInfo }
                ),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "restart_app executed successfully.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    private val IDevice.mcpDeviceInfo: McpDeviceInfo
        get() {
            return McpDeviceInfo(
                serial = this.serialNumber,
                name = this.name,
                isOnline = this.isOnline,
            )
        }
}
