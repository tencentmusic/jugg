package com.sickworm.intellij.jugg.ide

import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResponse

/**
 * API that IDE will call to interact with JuggManager.
 */
interface IJuggManagerCaller: Disposable {

    fun init()

    fun onSyncEvent(syncEvent: SyncEvent)

    fun runTask(options: JuggRunConfigurationOptions): ExecutionResult

    fun gradleCompile()

    fun restartApp()

    fun reportIssue()

    fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup

    fun getJuggRunSettingsComponent(): IJuggRunSettingsComponent

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse

    fun call(rpcRequest: RpcRequest): RpcResponse
}
