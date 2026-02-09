package com.sickworm.intellij.jugg.mcp

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

class JuggRunInvoker(
    private val juggManager: JuggManager,
    private val gitFileChangesDetector: GitFileChangesDetector,
) {

    fun runFirstConfiguration(isRpcMode: Boolean): JuggRunInvocationResult {
        val currentRunConfigurationList = RunManager.getInstance(juggManager.project)
            .getConfigurationSettingsList(JuggConfigurationType::class.java)
        @Suppress("UNCHECKED_CAST")
        val runConfiguration = (currentRunConfigurationList.firstOrNull()?.configuration
                as? RunConfigurationBase<JuggRunConfigurationOptions>)
            ?: return JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "Run configuration not found.",
            )

        val state = runConfiguration.state
            ?: return JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "Run configuration state is null.",
            )

        var runResultFinal: RunResult? = null
        val waitLock = Object()
        val compileUiHandler = object : JuggCompileUiHandler(
            juggManager.project,
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = isRpcMode,
            state.toCompileOptions(juggManager.pathManager),
            juggManager.logger,
        ) {
            override fun onEnd(runResult: RunResult) {
                synchronized(waitLock) {
                    runResultFinal = runResult
                    waitLock.notify()
                }
            }
        }

        val logCollector = RunLogCollector()
        JuggLogger.listenProjectLog(juggManager.project, logCollector)
        if (isRpcMode) {
            gitFileChangesDetector.updateChangedFiles()
        }

        SwingUtilities.invokeLater {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val executionResult = juggManager.runTask(state, compileUiHandler)
            val descriptor = RunContentDescriptor(
                executionResult.executionConsole,
                executionResult.processHandler,
                executionResult.executionConsole.component,
                runConfiguration.name,
            )
            RunContentManager.getInstance(juggManager.project).showRunContent(executor, descriptor)
        }

        synchronized(waitLock) {
            waitLock.wait()
        }
        JuggLogger.stopListenProjectLog(juggManager.project, logCollector)

        return JuggRunInvocationResult(
            isSuccess = true,
            runResult = runResultFinal,
            detail = logCollector.getAllLogs(),
        )
    }
}

data class JuggRunInvocationResult(
    val isSuccess: Boolean,
    val runResult: RunResult? = null,
    val detail: String = "",
    val errorMessage: String? = null,
)

