package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.JuggCompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationSmRunnerBridge
import com.sickworm.intellij.jugg.ide.JuggAndroidTestConsoleProperties
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.ai.mcp.RunLogCollector
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
) : IJuggConfigurationRunner {

    override val isCompiling: Boolean get() = currentTask?.isRunning == true

    override val currentIndicatorText: String
        get() = currentCompileUiHandler?.progressIndicator?.text.orEmpty()

    @Volatile
    private var currentTask: IJuggRunningTask? = null

    @Volatile
    private var currentCompileUiHandler: CompileUiHandler? = null


    override fun runTask(
        options: JuggGradleCompileOptions,
        compileUiHandler: CompileUiHandler,
        executor: Executor?,
        runProfile: RunProfile?,
        androidTestRunSpec: AndroidTestRunSpec?,
    ): ExecutionResult {
        if (ForceGradleCompileHelper.isCleanAndReinstallNextTime) {
            forceReInstallNextTime()
        }
        val processHandler = SimpleProcessHandler()
        val consoleView = createConsole(processHandler, androidTestRunSpec, executor, runProfile)
        processHandler.startNotify()
        compileUiHandler.processHandler = processHandler
        if (androidTestRunSpec != null) {
            val bridge = createAndroidTestBridge(processHandler)
            compileUiHandler.testEventSinkFactory = { deviceName, showDeviceSuite ->
                createAndroidTestEventSink(bridge, deviceName, showDeviceSuite)
            }
        }

        cancelCurrentTask(processHandler) {
            currentCompileUiHandler = compileUiHandler
            currentTask = juggRunningTaskCreator.createAndRun(options, compileUiHandler, androidTestRunSpec)
        }
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = false
        ForceGradleCompileHelper.isForceGradleCompileNextTime = false
        ForceGradleCompileHelper.isGradleCacheRefreshNextTime = false
        return DefaultExecutionResult(consoleView, processHandler)
    }


    private fun createConsole(
        processHandler: ProcessHandler,
        androidTestRunSpec: AndroidTestRunSpec?,
        executor: Executor?,
        runProfile: RunProfile?,
    ): ConsoleView {
        if (androidTestRunSpec == null || executor == null || runProfile == null) {
            val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            consoleView.attachToProcess(processHandler)
            return consoleView
        }
        val properties = JuggAndroidTestConsoleProperties(project, runProfile, executor, androidTestRunSpec)
        return SMTestRunnerConnectionUtil.createAndAttachConsole(
            JuggAndroidTestConsoleProperties.TEST_FRAMEWORK_NAME,
            processHandler,
            properties,
        )
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

    override fun forceReInstallNextTime() {
        // clear lastDeployOverlayIds to force re-reinstall
        deployHistoryManager.isCleanAndReinstall = true
        juggRunningTaskStatusManager.resetHasRun()
    }

    override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean, isAlwaysRestartApp: Boolean): JuggRunInvocationResult {
        return runFirstConfigurationWithSpec(
            isRpcMode = isRpcMode,
            isSkipDeploy = isSkipDeploy,
            isAlwaysRestartApp = isAlwaysRestartApp,
            androidTestRunSpec = null,
            buildTargetOverride = null,
        )
    }

    override fun runFirstConfigurationWithSpec(
        isRpcMode: Boolean,
        isSkipDeploy: Boolean,
        isAlwaysRestartApp: Boolean,
        androidTestRunSpec: AndroidTestRunSpec?,
        buildTargetOverride: BuildTarget?,
    ): JuggRunInvocationResult {
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
        val compileOptions = state.toCompileOptions(pathManager).let { options ->
            if (buildTargetOverride == null) options else options.copy(buildTarget = buildTargetOverride)
        }

        var runResultFinal: RunResult? = null
        val waitLock = Object()
        val compileUiHandler = object : JuggCompileUiHandler(
            project,
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = isRpcMode,
            isGradleCacheRefreshRequested = ForceGradleCompileHelper.isGradleCacheRefreshNextTime,
            juggGradleCompileOptions = compileOptions,
            logger = logger,
            isSkipDeploy = isSkipDeploy,
            isAlwaysRestartApp = isAlwaysRestartApp,
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
            val executionResult = runTask(compileOptions, compileUiHandler, null, null, androidTestRunSpec)
            val descriptor = createRunContentDescriptor(executionResult, runConfiguration.name)
            RunContentManager.getInstance(project).showRunContent(executor, descriptor)
        }

        synchronized(waitLock) {
            waitLock.wait()
        }
        JuggLogger.stopListenProjectLog(project, logCollector)

        val runResult = runResultFinal
        val isSuccess = runResult?.isInvocationSuccess(isSkipDeploy) ?: false
        val detail = logCollector.getAllLogs()
        return JuggRunInvocationResult(
            isSuccess = isSuccess,
            runResult = runResult,
            detail = detail,
            errorMessage = if (!isSuccess) runResult?.let { r ->
                if (r.isCompileSuccess) r.failedReason ?: "deploy failed" else "compile failed"
            } else null,
        )
    }
}

internal fun createRunContentDescriptor(
    executionResult: ExecutionResult,
    displayName: String,
): RunContentDescriptor {
    return RunContentDescriptor(
        executionResult.executionConsole,
        executionResult.processHandler,
        executionResult.executionConsole.component,
        displayName,
    ).apply {
        isActivateToolWindowWhenAdded = false
    }
}

/**
 * Creates the shared SM runner bridge used by one androidTest run.
 */
internal fun createAndroidTestBridge(
    processHandler: IProcessHandler,
): InstrumentationSmRunnerBridge {
    return InstrumentationSmRunnerBridge { message ->
        processHandler.notifyTextAvailable("$message\n", ProcessOutputType.STDOUT)
    }
}

/**
 * Creates the instrumentation sink used by one device in an androidTest run.
 */
internal fun createAndroidTestEventSink(
    bridge: InstrumentationSmRunnerBridge,
    deviceName: String,
    showDeviceSuite: Boolean,
): (InstrumentationEvent) -> Unit {
    bridge.startDevice(deviceName, showDeviceSuite = showDeviceSuite)
    return { event ->
        bridge.onEvent(event)
        if (event is InstrumentationEvent.SuiteFinished) {
            bridge.finishDevice()
        }
    }
}
