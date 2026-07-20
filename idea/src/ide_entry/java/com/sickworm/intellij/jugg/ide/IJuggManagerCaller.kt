package com.sickworm.intellij.jugg.ide

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import javax.swing.JComponent

/**
 * API that IDE will call to interact with JuggManager.
 */
interface IJuggManagerCaller: Disposable {

    fun init()

    fun onSyncEvent(syncEvent: SyncEvent)

    @Deprecated("for compatibility")
    fun runTask(options: JuggRunConfigurationOptions): ExecutionResult

    fun runTask(
        options: JuggRunConfigurationOptions,
        executor: Executor?,
        runProfile: RunProfile?,
        androidTestRunSpec: AndroidTestRunSpec?,
    ): ExecutionResult

    fun gradleCompile()

    fun restartApp()

    fun cleanAndReinstall()

    fun resetJuggCache()

    fun reportIssue()

    fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup

    fun getJuggRunSettingsComponent(): IJuggRunSettingsComponent

    fun getJuggControlPanel(page: String): JComponent

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse

    fun installSkills()

    fun checkUpdates()
}
