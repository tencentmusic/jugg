package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.ai.mcp.McpToolInvoker
import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry
import com.sickworm.intellij.jugg.ai.mcp.ProjectInitializationResult
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.project.runtime.RuntimeOwnerChangeEvent
import java.io.File

/** Owns the standalone MCP and project service lifecycle for one project. */
class StandaloneProjectRuntime internal constructor(
    projectDirectory: File,
    runtimeInfo: RuntimeInfo,
    activity: StandaloneDaemonActivity,
    toolRegistry: McpToolRegistry,
) : IMcpRuntime, AutoCloseable {
    private val services = StandaloneProjectServices(projectDirectory.canonicalFile, runtimeInfo, activity)
    private val configurationRunner = StandaloneConfigurationRunner(services, activity)
    private val gradleCompileHelper = StandaloneForceGradleCompileHelper(configurationRunner, services.configurationStore)
    private val invoker = McpToolInvoker(services.projectDir.path, this, toolRegistry)

    override val projectDir: String = services.projectDir.path
    override val compileLatestLogPath: String = "build/jugg/log/standlone_cli/compile_latest.log"
    override val logger: Logger = services.logger
    override val deployTargetManager: IDeployTargetManager = services.deployTargetManager
    override val deployStateManager: IDeployStateManager = services.deployStateManager
    override val deployFileManager: DeployFileManager = services.deployFileManager
    override val forceGradleCompileHelper: ForceGradleCompileHelper = gradleCompileHelper
    override val juggConfigurationRunner: IJuggConfigurationRunner = configurationRunner
    override val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker = services.compilerHelper
    val ownerChange: RuntimeOwnerChangeEvent? = services.ownerChange

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse = invoker.invokeMcp(request)

    override fun refreshChangedFilesForStatus() = services.refreshChangedFiles()

    override fun <T> withProjectStateLocked(action: () -> T): T {
        return services.runProjectWriteLocked("Read Jugg status", action)
    }

    override fun <T : Any> tryWithProjectStateLocked(action: () -> T): T? {
        return services.tryRunProjectWriteLocked("Read Jugg status", action)
    }

    override fun isAppReadyDeploy(): Boolean = deployStateManager.updateDeployState().isReadyDeploy

    override fun initializeProject(): ProjectInitializationResult = services.initializeProject()

    internal fun deployEnvironment() = services.deployEnvironment()

    override fun close() = services.close()
}
