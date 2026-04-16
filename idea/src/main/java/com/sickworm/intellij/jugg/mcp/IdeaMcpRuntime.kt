package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.mcp.actions.McpToolActionRegistry

class IdeaMcpRuntime(
    override val logger: Logger,
    override val project: Project,
    override val deployTargetManager: IDeployTargetManager,
    override val deployStateManager: IDeployStateManager,
    override val forceGradleCompileHelper: ForceGradleCompileHelper,
    override val juggConfigurationRunner: IJuggConfigurationRunner,
    override val deployFileManager: DeployFileManager,
) : IMcpRuntime {

    companion object {
        fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
            if (request.method != McpJsonRpc.Method.ToolsCall) {
                val response = McpBaseInvoker.mcpBaseInvoker.invokeMcp(request)
                return response
            }

            val toolName = (request.params as? Map<*, *>)?.get("name") as? String
            if (toolName == McpToolActionRegistry.ToolNames.LIST_PROJECTS) {
                return McpBaseInvoker.mcpBaseInvoker.invokeMcp(request)
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
