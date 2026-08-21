package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.google.gson.Gson
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressIndicatorListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.CompileTaskResult
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.getTargetDeviceSerialList
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModel
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.DeployProgress
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.ui.ProcessHandlerLoggerWrapper
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanelController
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.runtime.ILastCompileProjectRegistry
import com.sickworm.intellij.jugg.project.runtime.LastCompileProjectRegistry
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import javax.swing.SwingUtilities

private typealias JuggEventCategory = JuggEvent.Category
private typealias JuggEventLevel = JuggEvent.Level
private typealias JuggEventPhase = JuggEvent.Phase
private typealias JuggEventSource = JuggEvent.Source
private typealias JuggEventStatus = JuggEvent.Status

/**
 * Implementation of compilation and deployment.
 * [run] will be called when user click "Run" button.
 */
@Suppress("DialogTitleCapitalization")
class JuggRunningTask(
    private val options: JuggGradleCompileOptions,
    private val project: Project,
    private val juggServer: JuggServer,
    private val deployTargetManager: IDeployTargetManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val statusManager: IJuggRunningTaskStatusManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val juggCompileHelper: JuggCompilerHelper,
    private val juggDeployHelper: JuggDeployerHelper,
    private val initIncrementalCompileTask: () -> Unit,
    private val compileUiHandler: CompileUiHandler,
    private val eventModel: JuggControlPanelModel,
    private val taskRunnerManager: TaskRunnerManager,
    private val recoverAfterRuntimeOwnerChange: () -> Boolean,
    private val androidTestRunSpec: AndroidTestRunSpec? = null,
    private val lastCompileProjectRegistry: ILastCompileProjectRegistry = LastCompileProjectRegistry.INSTANCE,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggRunningTask"),
    private val controlPanelController: JuggControlPanelController? = null,
) : Task.Backgroundable(project, "Running Jugg..."), IJuggRunningTask {

    private val processHandler: IProcessHandler get() = compileUiHandler.processHandler
    private val androidTestResultModel: AndroidTestResultModel = AndroidTestResultModel()
    private val eventTaskId = UUID.randomUUID().toString()
    private val eventStartedAt = System.currentTimeMillis()
    private var hasTerminalEvent = false
    private var compileMode: JuggEvent.CompileMode? = null
    private var finalDeployType: JuggDeployData.DeployType? = null
    private var didInstall = false
    private var fallbackPath: String? = null

    private val indicatorListener = object : ProgressIndicatorListener {
        override fun cancelled() {
            logger.debug("[Jugg] progress indicator canceled, detach process, processCanceled=${processHandler.isCanceled}")
            processHandler.detachProcess()
        }
        override fun stopped() { }
    }

    override var isRunning: Boolean = false
        private set

    override fun run(indicator: ProgressIndicator) {
        taskRunnerManager.runProjectWriteLocked("Run Jugg") {
            runLocked(indicator, recoverAfterRuntimeOwnerChange())
        }
    }

    private fun runLocked(indicator: ProgressIndicator, runtimeOwnerChanged: Boolean) {
        val loggerListener = createRunProjectLogListener(processHandler)
        var isNeedResetHasRun = false
        try {
            if (TestModeManager.isRuntimeTestEnabled()) {
                RuntimeMockUtils.runTest(logger)
                return
            }

            controlPanelController?.refresh()
            recordEvent(
                category = JuggEventCategory.COMPILE,
                phase = JuggEventPhase.PREPARING,
                status = JuggEventStatus.STARTED,
                title = "Jugg task started",
                changedFiles = eventModel.snapshot().context.changedFiles,
            )

            statusManager.isProjectSwitchedThisRun = statusManager.isProjectSwitchedThisRun || runtimeOwnerChanged ||
                lastCompileProjectRegistry.detectSwitch(options.projectRootPath)

            dependencyChangeManager.onStartBuilding()
            JuggLogger.recreateLogFileIfDeleted(project)
            JuggLogger.listenProjectLog(project, loggerListener)
            juggServer.onCompile()

            isRunning = true
            showGreenDotOnRunToolWindow()
            initIndicator(indicator)
            if (compileUiHandler.isForceGradleCompile) {
                notifyFallback(project, "force fallback")
            }
            val runResult = doRun(options)
            isNeedResetHasRun = runResult.isNeedResetHasRun
            // for gradle compilation, compile success is ok to stage compile result
            // for incremental compilation, we need to deploy success to stage compile result
            val isSuccess = if (runResult.isGradleCompile) runResult.isCompileSuccess else runResult.isDeploySuccess
            val isCanceled = processHandler.isCanceled && !processHandler.isCanceledByNextTask
            dependencyChangeManager.onEndBuilding(isSuccess, isCanceled)
            if (runResult.isGradleCompile && !processHandler.isCanceled) {
                deployHistoryManager.isLastFullCompileFailed = !runResult.isCompileSuccess
            }
            compileUiHandler.onEnd(runResult)
            finishRunEvent(runResult)
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logger.warn("Run stop unexpected with ${e::class.java}:\n$sw\nRun stop unexpected.")
            dependencyChangeManager.onEndBuilding(isSuccess = false, isCancelled = false)
            finishEvent(JuggEventCategory.COMPILE, JuggEventStatus.FAILED, "Jugg task failed", e.message)
            compileUiHandler.onEnd(RunResult.FAILED)
        } finally {
            isRunning = false
            lastCompileProjectRegistry.record(options.projectRootPath)
            val isCanceled = processHandler.isCanceled && !processHandler.isCanceledByNextTask
            if (isCanceled) {
                isNeedResetHasRun = true
            }
            if (isNeedResetHasRun) {
                statusManager.resetHasRun()
            } else {
                statusManager.setHasRun(deployTargetManager.getTargetDeviceSerialList(compileUiHandler.targetDeviceSerial))
            }
            statusManager.isProjectSwitchedThisRun = false
            if (!hasTerminalEvent && isCanceled) {
                finishEvent(JuggEventCategory.COMPILE, JuggEventStatus.CANCELED, "Jugg task canceled")
            }
            JuggLogger.stopListenProjectLog(project, loggerListener)
            stop(indicator)
            controlPanelController?.refresh()
        }
    }

    private fun showGreenDotOnRunToolWindow() {
        prepareRunToolWindowOnTaskStart(statusManager.isFirstTimeRun(), compileUiHandler)
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.getToolWindow("Run")?.let {
                val icon = ExecutionUtil.getLiveIndicator(it.icon)
                it.setIcon(icon)
            }
        }
    }

    private fun initIndicator(indicator: ProgressIndicator) {
        logger.info("Jugg compile started.\n")
        compileUiHandler.progressIndicator = indicator
        indicatorListener.installToProgressIfPossible(indicator)
        indicator.text = "Compiling by Jugg..."
        indicator.isIndeterminate = true
    }

    private fun doRun(options: JuggGradleCompileOptions): RunResult {
        val detailMap = mutableMapOf<String, String>()
        detailMap["isForceGradleCompile"] = compileUiHandler.isForceGradleCompile.toString()

        recordEvent(
            category = JuggEventCategory.COMPILE,
            phase = JuggEventPhase.COMPILING,
            status = JuggEventStatus.STARTED,
            title = if (compileUiHandler.isForceGradleCompile) "Gradle compile started" else "Incremental compile started",
        )

        val compileTaskResult = juggCompileHelper.compile(
            options,
            compileUiHandler,
            isAndroidTestRun = androidTestRunSpec != null,
        )
        compileMode = if (compileTaskResult.isGradleCompile) JuggEvent.CompileMode.GRADLE else JuggEvent.CompileMode.INCREMENTAL
        detailMap["isGradleCompile"] = compileTaskResult.isGradleCompile.toString()
        detailMap["failed_reason"] = compileTaskResult.failedReason ?: "null"
        detailMap["inc_failed_reason"] = compileTaskResult.incrementalFailedReason ?: "null"
        if (compileTaskResult.isGradleCompile) {
            detailMap["isRemoteCompile"] = options.isRemoteCompile.toString()
            if (options.isRemoteCompile) {
                detailMap["remoteHost"] = options.remoteSshIp
            }
        }
        juggServer.report {
            action = "compile"
            isSuccess = compileTaskResult.isSuccess
            costTime = compileTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }
        recordEvent(
            category = JuggEventCategory.COMPILE,
            phase = JuggEventPhase.COMPILING,
            status = if (compileTaskResult.isSuccess) JuggEventStatus.SUCCEEDED else JuggEventStatus.FAILED,
            title = if (compileTaskResult.isSuccess) "Compile completed" else "Compile failed",
            detail = compileTaskResult.failedReason,
            durationMillis = compileTaskResult.costTime,
        )

        if (!compileTaskResult.isSuccess) {
            failedAndActiveRunWindowIfNotCanceled()
            return RunResult(isGradleCompile = compileTaskResult.isGradleCompile,
                isCompileSuccess = false, isDeploySuccess = false, isCancel = processHandler.isCanceled,
                errorLog = compileTaskResult.errorLog)
        }

        if (compileUiHandler.isSkipDeploy) {
            logger.info("Skip deploy.")
            if (compileTaskResult.isGradleCompile) {
                initIncrementalCompileTask.invoke()
            }
            // reset hasRun so next user-triggered compile won't show "no file changes"
            return RunResult(isGradleCompile = compileTaskResult.isGradleCompile,
                isCompileSuccess = true, isDeploySuccess = false, isNeedResetHasRun = true, isCancel = processHandler.isCanceled)
        }

        val devices = deployTargetManager.getTargetDevices(compileUiHandler.targetDeviceSerial)
        if (devices.isEmpty()) {
            val deployType = if (compileTaskResult.isGradleCompile) {
                "installing"
            } else {
                "deploying"
            }
            val failedReason = compileUiHandler.targetDeviceSerial?.let {
                "Device $it is not connected. Stop $deployType."
            } ?: "No device found. Stop $deployType."
            logger.warn(failedReason)
            failedAndActiveRunWindowIfNotCanceled()

            if (compileTaskResult.isGradleCompile) {
                initIncrementalCompileTask.invoke()
            }
            recordEvent(
                category = JuggEventCategory.DEPLOY,
                phase = JuggEventPhase.DEPLOYING,
                status = JuggEventStatus.FAILED,
                title = "Deploy failed",
                detail = failedReason,
            )
            return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = true,
                isDeploySuccess = false, isNeedResetHasRun = compileTaskResult.isGradleCompile, isCancel = processHandler.isCanceled,
                failedReason = failedReason)
        }

        var totalDeployTime = 0L
        val deployTaskResultList = mutableListOf<DeployTaskResult>()
        recordEvent(
            category = JuggEventCategory.DEPLOY,
            phase = JuggEventPhase.DEPLOYING,
            status = JuggEventStatus.STARTED,
            title = "Deploy started",
            detail = devices.joinToString { it.name },
        )
        val isMultipleDevices = devices.size > 1
        devices.forEachIndexed { index, device ->
            val isLastDevice = index == devices.size - 1
            val deployTaskResult = deployDevice(isMultipleDevices, isLastDevice, device, compileUiHandler.progressIndicator, compileTaskResult, detailMap)
            deployTaskResultList.add(deployTaskResult)
            totalDeployTime += deployTaskResult.costTime
        }

        val deployType = when {
            deployTaskResultList.any { it.deployType == JuggDeployData.DeployType.INSTALL } -> {
                JuggDeployData.DeployType.INSTALL
            }
            deployTaskResultList.any { it.deployType == JuggDeployData.DeployType.EMBEDDED } -> {
                JuggDeployData.DeployType.EMBEDDED
            }
            deployTaskResultList.any { it.deployType == JuggDeployData.DeployType.COMPAT_HOT_FIX } -> {
                JuggDeployData.DeployType.COMPAT_HOT_FIX
            }
            deployTaskResultList.any { it.deployType == JuggDeployData.DeployType.HOT_FIX } -> {
                JuggDeployData.DeployType.HOT_FIX
            }
            else -> {
                JuggDeployData.DeployType.HOT_RELOAD
            }
        }
        finalDeployType = deployType

        val isAllSuccess = deployTaskResultList.all { it.isSuccess }
        if (!isAllSuccess) {

            // not all device is success
            val isErrorCanFallback = deployTaskResultList.all { it.isCanFallback }
            logger.debug("Not all device is deploying success. isErrorCanFallback $isErrorCanFallback, " +
                    "isAutoFallbackToGradleWhenDeployError: ${JuggSettings.isAutoFallbackToGradleWhenDeployError}")
            val failedReason = if (deployTaskResultList.size == 1) {
                deployTaskResultList[0].failedReason ?: "deploy failed"
            } else {
                deployTaskResultList.joinToString(", ") { it.failedReason ?: "deploy failed" }
            }
            val isCanFallback = isErrorCanFallback && JuggSettings.isAutoFallbackToGradleWhenDeployError
            if (!isCanFallback) {
                // not all device can fall back
                if (compileTaskResult.isGradleCompile) {
                    initIncrementalCompileTask.invoke()
                }
                failedAndActiveRunWindowIfNotCanceled()

                // install failed, set flag, next time installing directly
                val isNeedResetHasRun = deployType == JuggDeployData.DeployType.INSTALL
                return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = true,
                    isDeploySuccess = false, isNeedResetHasRun = isNeedResetHasRun, isCancel = processHandler.isCanceled,
                    failedReason = failedReason)
            } else {
                // fallback to gradle compile
                logger.warn("Deploy Failed. Going to restart with fallback gradle compile.")
                recordEvent(
                    category = JuggEventCategory.DEPLOY,
                    phase = JuggEventPhase.DEPLOYING,
                    status = JuggEventStatus.WARNING,
                    title = "Deploy fallback requested",
                    detail = failedReason,
                    fallback = "Incremental failed → Gradle",
                )
                fallbackPath = "Incremental failed → Gradle"
                notifyFallback(project, failedReason)
                compileUiHandler.isForceGradleCompile = true
                return doRun(options)
            }
        }

        val totalTime = compileTaskResult.costTime + totalDeployTime
        val successLog = buildDeploySuccessLogLines(deployType, compileTaskResult.isGradleCompile, totalTime)
        logger.info(successLog.headline)
        logger.info(successLog.followUp)

        if (compileTaskResult.isGradleCompile) {
            initIncrementalCompileTask.invoke()
        }

        return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = true, isDeploySuccess = true, isCancel = processHandler.isCanceled)
    }

    private fun deployDevice(
        isMultipleDevices: Boolean,
        isLastDevice: Boolean,
        device: IDevice,
        indicator: ProgressIndicator,
        compileTaskResult: CompileTaskResult,
        detailMap: MutableMap<String, String>,
    ): DeployTaskResult {
        logger.debug("deployDevice: ${device.desc}, isMultipleDevices=$isMultipleDevices, isLastDevice=$isLastDevice")

        val suffix = if (isMultipleDevices) " on [${device.name}]" else ""
        if (compileTaskResult.isGradleCompile) {
            logger.info("Launching app$suffix...")
            indicator.text = "Launching app$suffix..."
        } else {
            logger.info("Deploying changes$suffix...")
            indicator.text = "Deploying changes$suffix..."
        }

        val deployTaskResult = juggDeployHelper.deploy(
            DeployOptions(
                device = device,
                isLastDevice = isLastDevice,
                isMultipleDevices = isMultipleDevices,
                processHandler = processHandler,
                progress = DeployProgress { text -> indicator.text = text },
                isInstall = compileTaskResult.isGradleCompile,
                compileUiHandler = compileUiHandler,
                androidTestRunSpec = androidTestRunSpec,
                androidTestResultModel = if (androidTestRunSpec != null) androidTestResultModel else null,
            )
        )
        detailMap["deploy_failed_reason"] = deployTaskResult.failedReason ?: ""
        detailMap["deploy_type"] = deployTaskResult.deployType?.toString() ?: ""
        detailMap["cost_time_except_check"] = deployTaskResult.costTimeExceptCheck.toString()
        detailMap["device_manufacturer"] = device.getProperty("ro.product.manufacturer") ?: "null"
        detailMap["device_model"] = device.getProperty("ro.product.model") ?: "null"
        juggServer.report {
            action = "deploy"
            isSuccess = deployTaskResult.isSuccess
            costTime = deployTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }

        if (deployTaskResult.isSuccess) {
            didInstall = didInstall || compileTaskResult.isGradleCompile ||
                    deployTaskResult.deployType in setOf(JuggDeployData.DeployType.INSTALL, JuggDeployData.DeployType.EMBEDDED)
            notifyLaunched(compileTaskResult.isGradleCompile, deployTaskResult.deployType, suffix, deployTaskResult.hasDeployChanges)
        }
        recordEvent(
            category = JuggEventCategory.DEPLOY,
            phase = JuggEventPhase.DEPLOYING,
            status = if (deployTaskResult.isSuccess) JuggEventStatus.SUCCEEDED else JuggEventStatus.FAILED,
            title = if (deployTaskResult.isSuccess) "Deploy to ${device.name} completed" else "Deploy to ${device.name} failed",
            detail = deployTaskResult.failedReason,
            durationMillis = deployTaskResult.costTime,
        )

        logger.debug("deployDevice: ${device.desc}, isMultipleDevices=$isMultipleDevices, isLastDevice=$isLastDevice, deployTaskResult=$deployTaskResult")
        return deployTaskResult
    }

    private fun notifyLaunched(isGradleCompile: Boolean, deployType: JuggDeployData.DeployType?, suffix: String, hasDeployChanges: Boolean) {
        val text = if (isGradleCompile) {
            "Launch succeeded$suffix"
        } else if (!hasDeployChanges) {
            "Deploy changes succeeded$suffix (no file changes)"
        } else if (deployType == JuggDeployData.DeployType.HOT_RELOAD) {
            "Deploy changes succeeded$suffix (no need restart App)"
        } else if (deployType == JuggDeployData.DeployType.COMPAT_HOT_FIX) {
            "Deploy changes succeeded$suffix (compat mode)"
        } else {
            "Deploy changes succeeded$suffix"
        }
        logger.debug("notifyLaunched $text")
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.notifyByBalloon("Run", MessageType.INFO, text)
        }
    }

    private fun failedAndActiveRunWindowIfNotCanceled() {
        if (processHandler.isCanceled) {
            return
        }
        compileUiHandler.showRunWindow()
    }

    private fun finishEvent(
        category: JuggEventCategory,
        status: JuggEventStatus,
        title: String,
        detail: String? = null,
        durationMillis: Long = System.currentTimeMillis() - eventStartedAt,
    ) {
        if (hasTerminalEvent) return
        hasTerminalEvent = true
        recordEvent(category, JuggEventPhase.COMPLETED, status, title, detail, durationMillis, isTerminal = true)
    }

    private fun finishRunEvent(result: RunResult) {
        when {
            result.isCancel -> finishEvent(JuggEventCategory.COMPILE, JuggEventStatus.CANCELED, "Jugg task canceled")
            !result.isCompileSuccess -> finishEvent(JuggEventCategory.COMPILE, JuggEventStatus.FAILED, "Compile failed", result.failedReason)
            result.isDeploySuccess -> finishEvent(JuggEventCategory.DEPLOY, JuggEventStatus.SUCCEEDED, "Deploy completed")
            result.failedReason != null -> finishEvent(JuggEventCategory.DEPLOY, JuggEventStatus.FAILED, "Deploy failed", result.failedReason)
            else -> finishEvent(JuggEventCategory.COMPILE, JuggEventStatus.SUCCEEDED, "Compile completed without deploy")
        }
    }

    private fun recordEvent(
        category: JuggEventCategory,
        phase: JuggEventPhase,
        status: JuggEventStatus,
        title: String,
        detail: String? = null,
        durationMillis: Long? = null,
        isTerminal: Boolean = false,
        changedFiles: List<JuggEvent.ChangedFileSnapshot> = emptyList(),
        fallback: String? = null,
    ) {
        eventModel.record(JuggEvent(
            taskId = eventTaskId,
            source = JuggEventSource.IDE,
            category = category,
            phase = phase,
            status = status,
            level = if (status in setOf(JuggEventStatus.FAILED, JuggEventStatus.WARNING)) JuggEventLevel.WARN else JuggEventLevel.INFO,
            title = title,
            detail = detail,
            durationMillis = durationMillis,
            compileMode = compileMode,
            deployType = finalDeployType,
            didInstall = didInstall,
            fallback = fallback ?: fallbackPath,
            changedFiles = changedFiles,
            isTaskTerminal = isTerminal,
        ))
    }

    private fun stop(indicator: ProgressIndicator) {
        indicator.stop()
        if (shouldDetachProcessOnTaskStop(processHandler.isCanceled)) {
            processHandler.detachProcess()
        }
        if (onFinishListener != null) {
            onFinishListener?.invoke()
            onFinishListener = null
        }
    }

    @Volatile
    private var onFinishListener: (() -> Unit)? = null

    override fun cancel(onFinishListener: () -> Unit) {
        if (isRunning) {
            this.onFinishListener = onFinishListener
            logger.debug("Try canceling process, processCanceled=${processHandler.isCanceled}, isCanceledByNextTask=${processHandler.isCanceledByNextTask}")
            processHandler.isCanceledByNextTask = true
            processHandler.detachProcess()
        } else {
            logger.debug("Process already terminated.")
            onFinishListener.invoke()
        }
    }

    companion object {

        fun notifyByBalloon(project: Project, message: String) {
            SwingUtilities.invokeLater {
                val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
                toolWindowManager.notifyByBalloon("Run", MessageType.INFO, message)
            }
        }

        fun notifyFallback(project: Project, reason: String) {
            val text = "Fallback to gradle compile. Reason: $reason"
            SwingUtilities.invokeLater {
                val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
                toolWindowManager.notifyByBalloon("Run", MessageType.WARNING, text)
            }
        }
    }
}

internal fun prepareRunToolWindowOnTaskStart(isFirstTimeRun: Boolean, compileUiHandler: CompileUiHandler) {
    if (isFirstTimeRun) {
        compileUiHandler.ensureRunWindowCreated()
    }
}

internal fun shouldDetachProcessOnTaskStop(isProcessCanceled: Boolean): Boolean {
    return !isProcessCanceled
}

internal fun createRunProjectLogListener(processHandler: IProcessHandler): ProcessHandlerLoggerWrapper {
    return ProcessHandlerLoggerWrapper(processHandler)
}

internal data class DeploySuccessLogLines(
    val headline: String,
    val followUp: String,
)

internal fun buildDeploySuccessLogLines(
    deployType: JuggDeployData.DeployType,
    isGradleCompile: Boolean,
    totalTimeMillis: Long,
): DeploySuccessLogLines {
    val totalSeconds = totalTimeMillis / 1000
    return when {
        deployType == JuggDeployData.DeployType.INSTALL && isGradleCompile -> {
            DeploySuccessLogLines(
                headline = "\nGradle BUILD_AND_INSTALL SUCCESSFUL in ${totalSeconds}s.",
                followUp = "App launched.",
            )
        }
        deployType == JuggDeployData.DeployType.INSTALL -> {
            DeploySuccessLogLines(
                headline = "\nJugg INSTALL SUCCESSFUL in ${totalSeconds}s.",
                followUp = "App launched.",
            )
        }
        else -> {
            DeploySuccessLogLines(
                headline = "\nJugg $deployType SUCCESSFUL in ${totalSeconds}s.",
                followUp = "App deployed.",
            )
        }
    }
}

private val IDevice.desc: String get() {
    // property name is gotten from IDevice
    val manufacturer = getProperty("ro.product.manufacturer") ?: "null"
    val model = getProperty("ro.product.model") ?: "null"
    return "Device: " +
            "name: ${name}, " +
            "manufacturer: ${manufacturer}, " +
            "model: ${model}, " +
            "version: ${version}, " +
            "isOnline: ${isOnline}, " +
            "clients: $clientCount"
}
