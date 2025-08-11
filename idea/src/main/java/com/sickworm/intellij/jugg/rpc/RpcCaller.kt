package com.sickworm.intellij.jugg.rpc

import com.google.gson.Gson
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.JuggCompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.logic.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.GitFileChangesDetector

class RpcCaller(private val juggManager: JuggManager, private val gitFileChangesDetector: GitFileChangesDetector) {

    fun call(rpcRequest: RpcRequest): RpcResponse {
        return when (rpcRequest.cmd) {
            RpcCommand.ECHO -> echo(rpcRequest)
            RpcCommand.RUN -> run(rpcRequest)
            else -> notSupport(rpcRequest)
        }
    }

    private fun echo(rpcRequest: RpcRequest): RpcResponse {
        return RpcResponse(
            status = RpcResult.OK,
            result = Gson().toJson(rpcRequest)
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun run(rpcRequest: RpcRequest): RpcResponse {
        val currentRunConfigurationList = RunManager.getInstance(juggManager.project)
            .getConfigurationSettingsList(JuggConfigurationType::class.java)
        @Suppress("UNCHECKED_CAST")
        val runConfiguration = (currentRunConfigurationList.firstOrNull()?.configuration
                as? RunConfigurationBase<JuggRunConfigurationOptions>)
        if (runConfiguration == null) {
            return RpcResponse(RpcResult.ErrorInternalServerError, "Run configuration not found.")
        }

        val state = runConfiguration.state
            ?: return RpcResponse(RpcResult.ErrorInternalServerError, "Run configuration state is null.")

        var runResultFinal: RunResult? = null
        val waitLock = Object()
        val compileUiHandler = object : JuggCompileUiHandler(
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = true,
            state.toCompileOptions(juggManager.pathManager),
            juggManager.logger
        ) {
            override fun onEnd(runResult: RunResult) {
                synchronized(waitLock) {
                    waitLock.notify()
                }
                runResultFinal = runResult
            }
        }
        val logCollector = LogCollector()
        JuggLogger.listenProjectLog(juggManager.project, logCollector)
        gitFileChangesDetector.updateChangedFiles()
        juggManager.runTask(state, compileUiHandler)
        synchronized(waitLock) {
            waitLock.wait()
        }
        JuggLogger.stopListenProjectLog(juggManager.project, logCollector)

        val result = mapOf(
            "runResult" to runResultFinal,
            "detail" to logCollector.getAllLogs(),
        )
        return RpcResponse(RpcResult.OK, Gson().toJson(result))
    }

    private fun notSupport(rpcRequest: RpcRequest): RpcResponse {
        return RpcResponse(RpcResult.ErrorMethodNotAllowed, "Command not supported: ${rpcRequest.cmd}.")
    }
}