package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.JuggCompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mcp.RunLogCollector
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.JuggPathManager
import javax.swing.SwingUtilities

/**
 * All about click RUN button.
 */
class JuggConfigurationRunner(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager,
    private val juggRunningTaskCreator: IJuggRunningTaskCreator,
    private val gitFileChangesDetector: GitFileChangesDetector,
    private val logger: Logger,
) {

    val isCompiling: Boolean get() = currentTask?.isRunning == true

    @Volatile
    private var currentTask: JuggRunningTask? = null

    fun runTask(options: JuggRunConfigurationOptions, compileUiHandler: CompileUiHandler): ExecutionResult {
        if (ForceGradleCompileHelper.isCleanAndReinstallNextTime) {
            forceReInstallNextTime()
        }
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val processHandler = SimpleProcessHandler()
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()
        compileUiHandler.processHandler = processHandler

        cancelCurrentTask(processHandler) {
            val task = juggRunningTaskCreator.create(options, compileUiHandler)
            currentTask = task
            ProgressManager.getInstance().run(task)
        }
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = false
        ForceGradleCompileHelper.isForceGradleCompileNextTime = false
        return DefaultExecutionResult(consoleView, processHandler)
    }

    private fun cancelCurrentTask(processHandler: IProcessHandler, onFinish: () -> Unit) {
        val currentTask = currentTask
        if (currentTask == null) {
            logger.debug("Current task is null")
            onFinish()
            return
        }
        if (!currentTask.isRunning) {
            logger.debug("Current task is not running")
            onFinish()
            return
        }
        logger.warn("Canceling task...")
        processHandler.notifyTextAvailable("Waiting last task finishing... \n\n", ProcessOutputType.STDOUT)
        currentTask.cancel(onFinish)
    }

    fun forceReInstallNextTime() {
        // clear lastDeployOverlayIds to force re-reinstall
        deployHistoryManager.isCleanAndReinstall = true
        juggRunningTaskStatusManager.resetHasRun()
    }

    fun runFirstConfiguration(isRpcMode: Boolean): JuggRunInvocationResult {
        val currentRunConfigurationList = RunManager.getInstance(project)
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
            project,
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = isRpcMode,
            state.toCompileOptions(pathManager),
            logger,
        ) {
            override fun onEnd(runResult: RunResult) {
                synchronized(waitLock) {
                    runResultFinal = runResult
                    waitLock.notify()
                }
            }
        }

        val logCollector = RunLogCollector()
        JuggLogger.listenProjectLog(project, logCollector)
        if (isRpcMode) {
            gitFileChangesDetector.updateChangedFiles()
        }

        SwingUtilities.invokeLater {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val executionResult = runTask(state, compileUiHandler)
            val descriptor = RunContentDescriptor(
                executionResult.executionConsole,
                executionResult.processHandler,
                executionResult.executionConsole.component,
                runConfiguration.name,
            )
            RunContentManager.getInstance(project).showRunContent(executor, descriptor)
        }

        synchronized(waitLock) {
            waitLock.wait()
        }
        JuggLogger.stopListenProjectLog(project, logCollector)

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
