package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager

/**
 * TestMcpRuntime supplies neutral MCP runtime behavior that individual tests may override.
 */
abstract class TestMcpRuntime : IMcpRuntime {
    override val projectDir: String = "/test-project"
    override val deployStateManager: IDeployStateManager? = null
    override val deployFileManager: DeployFileManager? = null
    override val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker? = null

    override fun refreshChangedFilesForStatus() = Unit

    override fun isAppReadyDeploy(): Boolean {
        return deployStateManager?.updateDeployState()?.isReadyDeploy ?: true
    }
}
