package com.sickworm.intellij.jugg.server

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.mcp.*

class IdeMcpRuntime(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
) : IMcpRuntime {

    override fun restartApp(serial: String?): McpToolResult {
        val targetDevice = deployTargetManager.getConnectedDevices().find { it.serialNumber == serial }
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
            AsDeployerCompat.getSelectedDevices(project)
        } else {
            listOf(targetDevice)
        }
        if (targetDevices.isNullOrEmpty()) {
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
            val result = deployTargetManager.restartApp(device)
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

    companion object {
        fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
            if (request.method != McpJsonRpc.Method.ToolsCall) {
                val response = McpInvoker.globalMcpInvoker.invokeMcp(request)
                return response
            }

            val toolName = (request.params as? Map<*, *>)?.get("name") as? String
            if (toolName == "list_projects") {
                val response = McpResultMapper().toolSuccess(
                    id = request.id,
                    toolResult = McpToolResult(
                        status = McpToolStatus.OK,
                        message = "list_projects executed successfully.",
                        data = mapOf(
                            "projects" to JuggInitializer.getInitializedProjectDirs().map {
                                McpProjectInfo(projectDir = it, initialized = true)
                            }
                        ),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                )
                return response
            }

            val projectDir = (request.params as? Map<*, *>)
                ?.let { params ->
                    @Suppress("UNCHECKED_CAST")
                    val args = params["arguments"] as? Map<String, Any?>
                    args?.get("projectDir") as? String
                }
                ?: run {
                    return McpResultMapper().toolError(
                        id = request.id,
                        errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                        message = "invoke_mcp failed. Reason: projectDir is required.")
                }

            val juggManager = JuggInitializer.getManager(projectDir)
                ?: run {
                    return McpResultMapper().toolError(
                        id = request.id,
                        errorCode = McpErrorCode.MCP_PROJECT_NOT_INITIALIZED,
                        message = "invoke_mcp failed. Reason: project is not initialized.")
                }

            val response = juggManager.invokeMcp(request)
            return response
        }
    }
}