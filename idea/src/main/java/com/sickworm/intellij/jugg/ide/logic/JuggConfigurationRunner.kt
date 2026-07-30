package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
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
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationSmRunnerBridge
import com.sickworm.intellij.jugg.ide.JuggAndroidTestConsoleProperties
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.ai.mcp.RunLogCollector
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.util.concurrent.FutureTask
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
        val fullBuildInfo = deployHistoryManager.getFullBuildInfo()
        val resolved = try {
            // RunManager selection and configuration options are mutable IDE state.
            callOnEdt {
                val runManager = RunManager.getInstance(project)
                val selected = runManager.selectedConfiguration
                val candidates = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java)
                val result = findJuggRunConfiguration(
                    selected,
                    candidates,
                    fullBuildInfo,
                    buildTargetOverride,
                )
                logger.debug(
                    "Resolve Jugg config: selected=${selected?.name}/${selected?.configuration?.javaClass?.name}, " +
                            "fullBuild=${fullBuildInfo?.compileCommand}/${fullBuildInfo?.buildTarget}, " +
                            "source=${result?.second}, chosen=${result?.first?.name}",
                )
                val (settings, source) = result ?: return@callOnEdt null
                if (source == "first") {
                    logger.warn(
                        "Cannot resolve selected or last full build Jugg configuration, fallback to first: " +
                                "chosen=${settings.name}, candidates=${candidates.map { it.name }}",
                    )
                }
                val state = (settings.configuration as JuggRunConfiguration).state ?: return@callOnEdt null
                val options = state.toCompileOptions(pathManager).let {
                    if (buildTargetOverride == null) it else it.copy(buildTarget = buildTargetOverride)
                }
                options to settings.name
            }
        } catch (e: Exception) {
            logger.warn("Resolve Jugg run configuration on EDT failed.", e)
            return JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "Resolve Jugg run configuration failed.",
            )
        }
        val (compileOptions, runConfigurationName) = resolved
            ?: return JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "Valid Jugg run configuration not found.",
            )

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
            val descriptor = createRunContentDescriptor(executionResult, runConfigurationName)
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

/** Chooses the selected Jugg configuration, then the last full-build match, then the first candidate. */
internal fun findJuggRunConfiguration(
    selectedSettings: RunnerAndConfigurationSettings?,
    candidateSettings: List<RunnerAndConfigurationSettings>,
    fullBuildInfo: FullBuildInfo?,
    buildTargetOverride: BuildTarget?,
): Pair<RunnerAndConfigurationSettings, String>? {
    if (selectedSettings?.configuration is JuggRunConfiguration) {
        return selectedSettings to "selected"
    }

    val candidates = candidateSettings.filter { it.configuration is JuggRunConfiguration }
    val fullBuildCommand = fullBuildInfo?.compileCommand?.takeIf { it.isNotBlank() }
    if (fullBuildCommand != null) {
        // BuildTarget disambiguates configurations that share the same Gradle command.
        candidates.firstOrNull { settings ->
            val options = (settings.configuration as JuggRunConfiguration).state ?: return@firstOrNull false
            val buildTarget = buildTargetOverride
                ?: if (options.enableAndroidTest) BuildTarget.ANDROID_TEST else BuildTarget.APP
            options.compileCommand == fullBuildCommand && buildTarget == fullBuildInfo.buildTarget
        }?.let {
            return it to "full_build_command_and_target"
        }
        // Keep using the previous command when its BuildTarget is no longer available.
        candidates.firstOrNull {
            (it.configuration as JuggRunConfiguration).state?.compileCommand == fullBuildCommand
        }?.let {
            return it to "full_build_command"
        }
    }

    return candidates.firstOrNull()?.let { it to "first" }
}

private fun <T> callOnEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return action()
    }
    val task = FutureTask<T> { action() }
    SwingUtilities.invokeAndWait(task)
    return task.get()
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
