package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner

/**
 * IMcpRuntime exposes project/runtime services required by MCP tool actions.
 */
interface IMcpRuntime {
    val logger: Logger
    val project: Project
    val deployTargetManager: IDeployTargetManager
    val deployStateManager: IDeployStateManager?
        get() = null
    val deployFileManager: DeployFileManager?
        get() = null
    val forceGradleCompileHelper: ForceGradleCompileHelper
    val juggConfigurationRunner: IJuggConfigurationRunner

    /**
     * Checks at status-query time whether incremental compile would fall back to Gradle.
     * Returns the fallback reason when fallback is required, or null when incremental compile
     * can proceed.  Defaults to null for runtimes that do not provide this check.
     */
    val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker?
        get() = null

    /**
     * Returns whether app process/deploy state is currently ready for runtime tools.
     * Default keeps backward compatibility for tests that do not provide deploy state.
     */
    fun isAppReadyDeploy(): Boolean {
        return deployStateManager?.updateDeployState()?.isReadyDeploy ?: true
    }
}

/**
 * IMcpInvoker executes one MCP JSON-RPC request and returns a JSON-RPC response.
 */
interface IMcpInvoker {
    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse
}
