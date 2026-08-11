package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.ai.mcp.actions.CompileJobManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager

/**
 * TestMcpRuntime supplies neutral MCP runtime behavior that individual tests may override.
 */
abstract class TestMcpRuntime : IMcpRuntime {
    override val projectDir: String = "/test-project"
    override val compileLatestLogPath: String = CompileJobManager.COMPILE_LATEST_LOG_PATH
    override val deployStateManager: IDeployStateManager? = null
    override val deployFileManager: DeployFileManager? = null
    override val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker? = null

    override fun refreshChangedFilesForStatus() = Unit

    override fun <T> withProjectStateLocked(action: () -> T): T = action()

    override fun <T : Any> tryWithProjectStateLocked(action: () -> T): T? = action()

    override fun isAppReadyDeploy(): Boolean {
        return deployStateManager?.updateDeployState()?.isReadyDeploy ?: true
    }

    override fun initializeProject(): ProjectInitializationResult {
        return ProjectInitializationResult(false, "Project initialization is not supported by this test runtime")
    }
}
