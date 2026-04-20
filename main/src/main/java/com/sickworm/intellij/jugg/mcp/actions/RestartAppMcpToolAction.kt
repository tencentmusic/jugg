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
import com.sickworm.intellij.jugg.mcp.util.LastDeployTimestampRegistry

/**
 * RestartAppMcpToolAction implements MCP tool `restart` and converts request arguments into tool execution and MCP result payloads.
 */
class RestartAppMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.RESTART

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
                message = "restart failed. Reason: Failed to restart app. Please check log in \\\$PROJECT_DIR/build/jugg/log/compile_latest.log\"",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INTERNAL_ERROR,
            )
        }
        val waitResult = McpAppReadyGuard.waitAfterMutating(runtime, toolName)
        if (!waitResult.isReady) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = waitResult.reason ?: "restart failed. Reason: app is not ready after restart.",
                data = mapOf("readyChecks" to waitResult.checks),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INTERNAL_ERROR,
            )
        }
        return McpToolResult(
            status = McpToolStatus.OK,
            message = "restart executed successfully.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = null,
        ).also {
            // Record restart completion as deploy timestamp baseline for wait-logs.
            runCatching { runtime.project.basePath }
                .getOrNull()
                ?.let { dir -> LastDeployTimestampRegistry.INSTANCE.recordNow(dir) }
        }
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
            message = "restart failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }
}
