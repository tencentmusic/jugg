package com.sickworm.intellij.jugg.rpc

import com.google.gson.Gson
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.JuggCompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.logic.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import javax.swing.SwingUtilities


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
        val isRpcMode = rpcRequest.args?.get("isRpcMode") as? Boolean ?: true
        val waitLock = Object()
        val compileUiHandler = object : JuggCompileUiHandler(
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = isRpcMode,
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
        if (isRpcMode) {
            // not modify files in IDE, needs to update changed files first
            gitFileChangesDetector.updateChangedFiles()
        }

        SwingUtilities.invokeLater {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val executionResult = juggManager.runTask(state, compileUiHandler)
            val descriptor = RunContentDescriptor(
                executionResult.executionConsole, executionResult.processHandler,
                executionResult.executionConsole.component, runConfiguration.name
            )
            RunContentManager.getInstance(juggManager.project).showRunContent(executor, descriptor)
        }

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