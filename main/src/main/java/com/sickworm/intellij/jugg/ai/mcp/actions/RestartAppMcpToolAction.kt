package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.LastDeployTimestampRegistry

/**
 * RestartAppMcpToolAction implements MCP tool `restart` and converts request arguments into tool execution and MCP result payloads.
 */
class RestartAppMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.RESTART

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Restart app process on the resolved target devices.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "serial" to McpToolSchemas.serialProperty,
                "waitAppReadyAfterSuccess" to McpToolSchemas.waitAppReadyAfterSuccessProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val waitAppReadyAfterSuccess = arguments["waitAppReadyAfterSuccess"] as? Boolean ?: false
        return restartAppAction(runtime, waitAppReadyAfterSuccess, arguments.deviceSerial())
    }

    private fun restartAppAction(
        runtime: IMcpRuntime,
        waitAppReadyAfterSuccess: Boolean,
        targetDeviceSerial: String?,
    ): McpToolResult {
        val targetDevices = runtime.deployTargetManager.getTargetDevices(targetDeviceSerial)
        if (targetDevices.isEmpty()) {
            val reason = targetDeviceSerial?.let { "Device $it is not online." }
                ?: "No connected device is available."
            return noDeviceResult(reason)
        }
        val failedDevices = targetDevices.filterNot { device ->
            runCatching { runtime.deployTargetManager.restartApp(device) }.getOrDefault(false)
        }
        if (failedDevices.isNotEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart failed. Reason: Failed to restart app on " +
                    failedDevices.joinToString { it.serialNumber } +
                    ". Please check log in \\\$PROJECT_DIR/build/jugg/log/compile_latest.log",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INTERNAL_ERROR,
            )
        }
        if (waitAppReadyAfterSuccess) {
            val waitResult = McpAppReadyGuard.waitAfterMutating(runtime, toolName, targetDeviceSerial)
            if (!waitResult.isReady) {
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = waitResult.reason ?: "restart failed. Reason: app is not ready after restart.",
                    data = mapOf("readyChecks" to waitResult.checks),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.INTERNAL_ERROR,
                )
            }
        }
        return McpToolResult(
            status = McpToolStatus.OK,
            message = "restart executed successfully.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = null,
        ).also {
            // Record restart completion as deploy timestamp baseline for wait-logs.
            runtime.projectDir.takeIf { it.isNotBlank() }
                ?.let { dir ->
                    LastDeployTimestampRegistry.INSTANCE.recordNow(dir, targetDeviceSerial)
                    if (targetDeviceSerial == null) {
                        targetDevices.forEach { device ->
                            LastDeployTimestampRegistry.INSTANCE.recordNow(dir, device.serialNumber)
                        }
                    }
                }
        }
    }

    private fun noDeviceResult(reason: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "restart failed. Reason: $reason",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }
}
