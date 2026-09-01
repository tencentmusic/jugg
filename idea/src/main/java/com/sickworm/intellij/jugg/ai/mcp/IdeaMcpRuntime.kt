package com.sickworm.intellij.jugg.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.ai.mcp.actions.ListProjectsMcpToolAction
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry
import com.sickworm.intellij.jugg.ai.mcp.actions.CompileJobManager
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager

class IdeaMcpRuntime(
    override val logger: Logger,
    override val projectDir: String,
    override val deployTargetManager: IDeployTargetManager,
    override val deployStateManager: IDeployStateManager,
    override val forceGradleCompileHelper: ForceGradleCompileHelper,
    override val juggConfigurationRunner: IJuggConfigurationRunner,
    override val deployFileManager: DeployFileManager,
    override val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker,
    private val gitFileChangesDetector: GitFileChangesDetector,
    private val taskRunnerManager: TaskRunnerManager,
    private val recoverAfterRuntimeOwnerChange: () -> Boolean,
) : IMcpRuntime {

    override val compileLatestLogPath: String = CompileJobManager.COMPILE_LATEST_LOG_PATH

    override fun refreshChangedFilesForStatus() {
        gitFileChangesDetector.updateChangedFiles()
    }

    override fun <T> withProjectStateLocked(action: () -> T): T {
        return taskRunnerManager.runProjectWriteLocked("Read Jugg status") {
            recoverAfterRuntimeOwnerChange()
            action()
        }
    }

    override fun <T : Any> tryWithProjectStateLocked(action: () -> T): T? {
        return taskRunnerManager.tryRunProjectWriteLocked("Read Jugg status") {
            recoverAfterRuntimeOwnerChange()
            action()
        }
    }

    override fun isAppReadyDeploy(): Boolean {
        return deployStateManager.updateDeployState().isReadyDeploy
    }

    override fun initializeProject(): ProjectInitializationResult {
        return ProjectInitializationResult(false, "Project initialization is not supported by IDEA runtime")
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
                ?: return uninitializedProjectError(request.id, normalizedProjectDir)

            val response = juggManager.invokeMcp(withNormalizedProjectDir(request, normalizedProjectDir))
            return response
        }

        private fun uninitializedProjectError(requestId: Any?, requestedProjectDir: String): McpJsonRpcResponse {
            val projects = initializedProjectList()
            return McpResultMapper().toolError(
                id = requestId,
                errorCode = McpErrorCode.PROJECT_NOT_INITIALIZED,
                message = uninitializedProjectMessage(requestedProjectDir, projects),
                data = mapOf("projects" to projects),
            )
        }

        private fun initializedProjectList(): List<McpProjectInfo> {
            val data = ListProjectsMcpToolAction().listProjectsAction().data as? Map<*, *>
            val projects = data?.get("projects") as? List<*> ?: return emptyList()
            return projects.filterIsInstance<McpProjectInfo>()
        }

        private fun uninitializedProjectMessage(
            requestedProjectDir: String,
            projects: List<McpProjectInfo>,
        ): String {
            val initializedBlock = if (projects.isEmpty()) {
                "Initialized projects: (none)"
            } else {
                buildString {
                    appendLine("Initialized projects:")
                    projects.forEach { append("  ").appendLine(it.projectDir) }
                }.trimEnd()
            }
            return "invoke_mcp failed. Reason: project is not initialized.\n" +
                "Requested: $requestedProjectDir\n" +
                initializedBlock
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
