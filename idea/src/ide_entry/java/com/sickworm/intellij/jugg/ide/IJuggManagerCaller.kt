package com.sickworm.intellij.jugg.ide

import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec

/**
 * API that IDE will call to interact with JuggManager.
 */
interface IJuggManagerCaller: Disposable {

    fun init()

    fun onSyncEvent(syncEvent: SyncEvent)

    fun runTask(
        options: JuggRunConfigurationOptions,
        androidTestRunSpec: AndroidTestRunSpec? = null,
    ): ExecutionResult

    fun gradleCompile()

    fun restartApp()

    fun reportIssue()

    fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup

    fun getJuggRunSettingsComponent(): IJuggRunSettingsComponent

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse

    fun installSkills()
}
