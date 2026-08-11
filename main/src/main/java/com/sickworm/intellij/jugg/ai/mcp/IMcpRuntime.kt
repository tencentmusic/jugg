package com.sickworm.intellij.jugg.ai.mcp

import com.intellij.openapi.diagnostic.Logger
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
    val projectDir: String
    val compileLatestLogPath: String
    val deployTargetManager: IDeployTargetManager
    val deployStateManager: IDeployStateManager?
    val deployFileManager: DeployFileManager?
    val forceGradleCompileHelper: ForceGradleCompileHelper
    val juggConfigurationRunner: IJuggConfigurationRunner

    /**
     * Checks at status-query time whether incremental compile would fall back to Gradle.
     * Returns the fallback reason when fallback is required, or null when incremental compile
     * can proceed.
     */
    val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker?

    /**
     * Refreshes file-change state before status snapshots are read.
     */
    fun refreshChangedFilesForStatus()

    /** Serializes status refresh and snapshot reads with project write transactions. */
    fun <T> withProjectStateLocked(action: () -> T): T

    /** Reads project state under the project lock only when the lock is immediately available. */
    fun <T : Any> tryWithProjectStateLocked(action: () -> T): T?

    /**
     * Returns whether app process/deploy state is currently ready for runtime tools.
     */
    fun isAppReadyDeploy(): Boolean

    /** Creates or selects the standalone run configuration used by compile tools. */
    fun initializeProject(): ProjectInitializationResult
}

/** Describes the selected run configuration after project initialization. */
data class ProjectInitializationResult(
    val isSuccess: Boolean,
    val message: String,
    val configurationId: String? = null,
    val configurationName: String? = null,
    val compileCommand: String? = null,
)

/**
 * IMcpInvoker executes one MCP JSON-RPC request and returns a JSON-RPC response.
 */
interface IMcpInvoker {
    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse
}
