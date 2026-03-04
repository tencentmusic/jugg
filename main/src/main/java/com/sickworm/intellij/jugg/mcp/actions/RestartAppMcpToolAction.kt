package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

/**
 * RestartAppMcpToolAction implements MCP tool `restart_app` and converts request arguments into tool execution and MCP result payloads.
 */
class RestartAppMcpToolAction : McpToolAction {
    override val toolName: String = "restart_app"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Restart app process on IDE selected device(s).",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return restartAppAction(runtime)
    }

    private fun restartAppAction(runtime: IMcpRuntime): McpToolResult {
        val targetDevice = resolveOnlineDevice(runtime) ?: return noDeviceResult()
        val isSuccess = runtime.deployTargetManager.restartApp(targetDevice)
        if (!isSuccess) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: Failed to restart app. Please check log in \\\$PROJECT_DIR/build/jugg/log/compile_latest.log\"",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
        val waitResult = McpAppReadyGuard.waitAfterMutating(runtime, toolName)
        if (!waitResult.isReady) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = waitResult.reason ?: "restart_app failed. Reason: app is not ready after restart.",
                data = mapOf("readyChecks" to waitResult.checks),
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

    private fun resolveOnlineDevice(runtime: IMcpRuntime): IDevice? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        return selectionResult.device
    }

    private fun noDeviceResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "restart_app failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }
}
