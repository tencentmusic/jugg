package com.sickworm.intellij.jugg.ide

import com.android.ddmlib.IDevice
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
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import java.io.PrintWriter
import java.io.StringWriter
import javax.swing.SwingUtilities

/**
 * Implementation of compilation and deployment.
 * [run] will be called when user click "Run" button.
 */
@Suppress("DialogTitleCapitalization")
class JuggRunningTask(
    private val project: Project,
    private val juggServer: JuggServer,
    private val deployTargetManager: IDeployTargetManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val statusManager: IJuggRunningTaskStatusManager,
    private val processHandler: SimpleProcessHandler,
    private val compileTask: (indicator: ProgressIndicator, forceFullCompile: Boolean) -> CompileTaskResult,
    private val deployTask: (device: IDevice, forceInstall: Boolean, isLastDevice: Boolean) -> DeployTaskResult,
    private val initIncrementalCompileTask: () -> Unit,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggRunningTask"),
) : Task.Backgroundable(project, "Running Jugg...") {

    private val indicatorListener = object : ProgressIndicatorListener {
        override fun cancelled() { processHandler.detachProcess() }
        override fun stopped() { }
    }

    var isRunning: Boolean = false
        private set

    override fun run(indicator: ProgressIndicator) {
        val loggerListener = ProcessHandlerLoggerWrapper(processHandler)
        var isNeedResetHasRun = false
        try {
            dependencyChangeManager.onStartBuilding()
            JuggLogger.recreateLogFileIfDeleted(project)
            JuggLogger.listenProjectLog(project, loggerListener)
            juggServer.onCompile()

            isRunning = true
            showGreenDotOnRunToolWindow()
            initIndicator(indicator)
            val runResult = doRun(indicator, false)
            isNeedResetHasRun = runResult.isNeedResetHasRun
            dependencyChangeManager.onEndBuilding(runResult.isDeploySuccess)
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logger.warn("Run stop unexpected with ${e::class.java}:\n$sw\nRun stop unexpected.")
            dependencyChangeManager.onEndBuilding(false)
        } finally {
            isRunning = false
            val isCanceled = processHandler.isCanceled && !processHandler.isCanceledByNextTask
            if (isCanceled) {
                isNeedResetHasRun = true
            }
            if (isNeedResetHasRun) {
                statusManager.resetHasRun()
            } else {
                statusManager.setHasRun(deployTargetManager.getDeviceNameList())
            }
            JuggLogger.stopListenProjectLog(project, loggerListener)
            stop(indicator)
        }
    }

    private fun showGreenDotOnRunToolWindow() {
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.getToolWindow("Run")?.let {
                val icon = ExecutionUtil.getLiveIndicator(it.icon)
                if (statusManager.isFirstTimeRun()) {
                    it.activate(null)
                }
                it.setIcon(icon)
            }
        }
    }

    private fun initIndicator(indicator: ProgressIndicator) {
        logger.info("Jugg compile started.\n")
        indicatorListener.installToProgressIfPossible(indicator)
        indicator.text = "Compiling by Jugg..."
        indicator.isIndeterminate = true
    }

    private fun doRun(indicator: ProgressIndicator, isForceGradleCompile: Boolean): RunResult {
        val detailMap = mutableMapOf<String, String>()
        detailMap["isForceGradleCompile"] = isForceGradleCompile.toString()

        val compileTaskResult = compileTask(indicator, isForceGradleCompile)
        detailMap["isGradleCompile"] = compileTaskResult.isGradleCompile.toString()
        detailMap["failed_reason"] = compileTaskResult.failedReason ?: "null"
        detailMap["inc_failed_reason"] = compileTaskResult.incrementalFailedReason ?: "null"
        juggServer.report {
            action = "compile"
            isSuccess = compileTaskResult.isSuccess
            costTime = compileTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }

        if (!compileTaskResult.isSuccess) {
            failedAndActiveRunWindowIfNotCanceled()
            return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = false, isDeploySuccess = false)
        }

        val devices = deployTargetManager.getDevices()
        if (devices.isEmpty()) {
            val deployType = if (compileTaskResult.isGradleCompile) {
                "installing"
            } else {
                "deploying"
            }
            logger.warn("No device found. Stop $deployType.")
            failedAndActiveRunWindowIfNotCanceled()

            if (compileTaskResult.isGradleCompile) {
                initIncrementalCompileTask.invoke()
            }
            return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = true,
                isDeploySuccess = false, isNeedResetHasRun = compileTaskResult.isGradleCompile)
        }

        var totalDeployTime = 0L
        val deployTaskResultList = mutableListOf<DeployTaskResult>()
        val isMultipleDevices = devices.size > 1
        devices.forEachIndexed { index, device ->
            val isLastDevice = index == devices.size - 1
            val deployTaskResult = deployDevice(isMultipleDevices, isLastDevice, device, indicator, compileTaskResult, detailMap)
            deployTaskResultList.add(deployTaskResult)
            totalDeployTime += deployTaskResult.costTime
        }

        val deployType = when {
            deployTaskResultList.any { it.deployType == JuggDeployData.DeployType.INSTALL } -> {
                JuggDeployData.DeployType.INSTALL
            }
            deployTaskResultList.any { it.deployType == JuggDeployData.DeployType.HOT_FIX } -> {
                JuggDeployData.DeployType.HOT_FIX
            }
            else -> {
                JuggDeployData.DeployType.HOT_RELOAD
            }
        }

        val isAllSuccess = deployTaskResultList.all { it.isSuccess }
        if (!isAllSuccess) {

            // not all device is success
            logger.debug("Not all device is deploying success.")
            if (!deployTaskResultList.all { it.isCanFallback }) {
                // not all device can fall back
                if (compileTaskResult.isGradleCompile) {
                    initIncrementalCompileTask.invoke()
                }
                failedAndActiveRunWindowIfNotCanceled()

                // install failed, set flag, next time installing directly
                val isNeedResetHasRun = deployType == JuggDeployData.DeployType.INSTALL
                return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = true,
                    isDeploySuccess = false, isNeedResetHasRun = isNeedResetHasRun)
            } else {
                // fallback to gradle compile
                logger.warn("Deploy Failed. Going to restart with fallback gradle compile.")
                val failedReason = if (deployTaskResultList.size == 1) {
                    deployTaskResultList[0].failedReason ?: "See log for details."
                } else {
                    deployTaskResultList.joinToString(", ") { it.failedReason ?: "See log for details." }
                }
                notifyFallback(project, failedReason)
                return doRun(indicator, true)
            }
        }

        val totalTime = compileTaskResult.costTime + totalDeployTime
        when (deployType) {
            JuggDeployData.DeployType.INSTALL -> {
                logger.info("\nGradle BUILD_AND_INSTALL SUCCESSFUL in ${totalTime / 1000}s.")
                logger.info("App launched.")
            }
            else -> {
                logger.info("\nJugg $deployType SUCCESSFUL in ${totalTime / 1000}s.")
                logger.info("App deployed.")
            }
        }

        if (compileTaskResult.isGradleCompile) {
            initIncrementalCompileTask.invoke()
        }

        return RunResult(isGradleCompile = compileTaskResult.isGradleCompile, isCompileSuccess = true, isDeploySuccess = true)
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

        val deployTaskResult = deployTask(device, compileTaskResult.isGradleCompile, isLastDevice)
        detailMap["deploy_failed_reason"] = deployTaskResult.failedReason ?: ""
        detailMap["deploy_type"] = deployTaskResult.deployType?.toString() ?: ""
        juggServer.report {
            action = "deploy"
            isSuccess = deployTaskResult.isSuccess
            costTime = deployTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }

        if (deployTaskResult.isSuccess) {
            notifyLaunched(compileTaskResult.isGradleCompile, deployTaskResult.deployType, suffix)
        }

        logger.debug("deployDevice: ${device.desc}, isMultipleDevices=$isMultipleDevices, isLastDevice=$isLastDevice, deployTaskResult=$deployTaskResult")
        return deployTaskResult
    }

    private fun notifyLaunched(isGradleCompile: Boolean, deployType: JuggDeployData.DeployType?, suffix: String) {
        val text = if (isGradleCompile) {
            "Launch succeeded$suffix"
        } else if (deployType == JuggDeployData.DeployType.HOT_RELOAD) {
            "Deploy changes succeeded$suffix (no need restart App)"
        } else {
            "Deploy changes succeeded$suffix"
        }
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.notifyByBalloon("Run", MessageType.INFO, text)
        }
    }

    private fun failedAndActiveRunWindowIfNotCanceled() {
        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            return
        }
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.getToolWindow("Run")?.activate(null)
        }
    }

    private fun stop(indicator: ProgressIndicator) {
        indicator.stop()
        if (!processHandler.isProcessTerminated) {
            processHandler.detachProcess()
        }
        if (onFinishListener != null) {
            onFinishListener?.invoke()
            onFinishListener = null
        }
    }

    @Volatile
    private var onFinishListener: (() -> Unit)? = null

    fun cancel(onFinishListener: () -> Unit) {
        if (isRunning) {
            this.onFinishListener = onFinishListener
            logger.debug("Try canceling process...")
            processHandler.isCanceledByNextTask = true
            processHandler.detachProcess()
        } else {
            logger.debug("Process already terminated.")
            onFinishListener.invoke()
        }
    }

    companion object {

        fun notifyFallback(project: Project, reason: String) {
            val text = "Fallback to gradle compile. Reason: $reason"
            SwingUtilities.invokeLater {
                val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
                toolWindowManager.notifyByBalloon("Run", MessageType.WARNING, text)
            }
        }
    }
}

private data class RunResult(
    val isGradleCompile: Boolean,
    val isCompileSuccess: Boolean,
    val isDeploySuccess: Boolean,
    val isNeedResetHasRun: Boolean = false,
)

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
            "clients: ${clients.size}"
}