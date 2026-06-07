package com.sickworm.intellij.jugg.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.ProjectDirNormalizer

class IdeaMcpRuntime(
    override val logger: Logger,
    override val project: Project,
    override val deployTargetManager: IDeployTargetManager,
    override val deployStateManager: IDeployStateManager,
    override val forceGradleCompileHelper: ForceGradleCompileHelper,
    override val juggConfigurationRunner: IJuggConfigurationRunner,
    override val deployFileManager: DeployFileManager,
    override val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker,
    private val gitFileChangesDetector: GitFileChangesDetector,
) : IMcpRuntime {

    override fun refreshChangedFilesForStatus() {
        gitFileChangesDetector.updateChangedFiles()
    }

    companion object {
        fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
            if (request.method != McpJsonRpc.Method.ToolsCall) {
                val response = McpBaseInvoker.mcpBaseInvoker.invokeMcp(request)
                return response
            }

            val toolName = (request.params as? Map<*, *>)?.get("name") as? String
            if (toolName in McpToolActionRegistry.noProjectDirTools) {
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
                        errorCode = McpErrorCode.INVALID_PARAMS,
                        message = "invoke_mcp failed. Reason: projectDir is required.")
                }

            val normalizedProjectDir = ProjectDirNormalizer.normalizeProjectDir(projectDir)
            val juggManager = JuggInitializer.getManager(normalizedProjectDir)
                ?: run {
                    return McpResultMapper().toolError(
                        id = request.id,
                        errorCode = McpErrorCode.PROJECT_NOT_INITIALIZED,
                        message = "invoke_mcp failed. Reason: project is not initialized.")
                }

            val response = juggManager.invokeMcp(withNormalizedProjectDir(request, normalizedProjectDir))
            return response
        }

        private fun withNormalizedProjectDir(
            request: McpJsonRpcRequest,
            normalizedProjectDir: String,
        ): McpJsonRpcRequest {
            val params = request.params as? Map<*, *> ?: return request
            @Suppress("UNCHECKED_CAST")
            val arguments = (params["arguments"] as? Map<String, Any?>)?.toMutableMap() ?: return request
            arguments["projectDir"] = normalizedProjectDir
            val patchedParams = params.toMutableMap().apply {
                this["arguments"] = arguments
            }
            return request.copy(params = patchedParams)
        }
    }
}
