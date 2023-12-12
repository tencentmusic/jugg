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
import com.jetbrains.rd.util.concurrentMapOf
import com.sickworm.intellij.jugg.compiler.CompileTaskResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.JuggReporter
import com.sickworm.intellij.jugg.project.JuggException
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
    private val juggReporter: JuggReporter,
    private val deployTargetManager: IDeployTargetManager,
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
        try {
            JuggLogger.recreateLogFileIfDeleted(project)
            JuggLogger.listenProjectLog(project, loggerListener)
            juggReporter.onCompile()

            isRunning = true
            showGreenDotOnRunToolWindow()
            initIndicator(indicator)
            doRun(indicator, false)
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logger.warn("Run stop unexpected with ${e::class.java}:\n$sw\nRun stop unexpected.")
        } finally {
            isRunning = false
            if (processHandler.isCanceled && !processHandler.isCanceledByNextTask) {
                resetHasRun(project)
            } else {
                setHasRun(project, deployTargetManager.getDeviceNameList())
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
                if (isFirstTimeRun(project)) {
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

    private fun doRun(indicator: ProgressIndicator, isForceGradleCompile: Boolean) {
        val detailMap = mutableMapOf<String, String>()
        detailMap["isForceGradleCompile"] = isForceGradleCompile.toString()

        val compileTaskResult = compileTask(indicator, isForceGradleCompile)
        detailMap["isGradleCompile"] = compileTaskResult.isGradleCompile.toString()
        juggReporter.report {
            action = "compile"
            isSuccess = compileTaskResult.isSuccess
            costTime = compileTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }

        if (!compileTaskResult.isSuccess) {
            failedAndActiveRunWindowIfNotCanceled()
            return
        }

        val devices = deployTargetManager.getDevices()
        if (devices.isEmpty()) {
            throw JuggException.deviceNotFound()
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

        if (deployTaskResultList.any { !it.isSuccess }) {
            // not all device is success
            if (!deployTaskResultList.all { it.isCanFallback }) {
                // not all device can fall back
                if (compileTaskResult.isGradleCompile) {
                    initIncrementalCompileTask.invoke()
                }
                failedAndActiveRunWindowIfNotCanceled()
            } else {
                // fallback to gradle compile
                logger.warn("Deploy Failed. Going to restart with fallback gradle compile.")
                val failedReason = if (deployTaskResultList.size == 1) {
                    deployTaskResultList[0].failedReason ?: "See log for details."
                } else {
                    deployTaskResultList.joinToString(", ") { it.failedReason ?: "See log for details." }
                }
                notifyFallback(project, failedReason)
                doRun(indicator, true)
            }
            return
        }

        val totalTime = compileTaskResult.costTime + totalDeployTime
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
    }

    private fun deployDevice(
        isMultipleDevices: Boolean,
        isLastDevice: Boolean,
        device: IDevice,
        indicator: ProgressIndicator,
        compileTaskResult: CompileTaskResult,
        detailMap: MutableMap<String, String>,
    ): DeployTaskResult {
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
        juggReporter.report {
            action = "deploy"
            isSuccess = deployTaskResult.isSuccess
            costTime = deployTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }

        if (deployTaskResult.isSuccess) {
            notifyLaunched(compileTaskResult.isGradleCompile, deployTaskResult.deployType, suffix)
        }

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
        private var isFirstTimeRun = concurrentMapOf<String, String?>()

        fun isFirstTimeRun(project: Project, runningDevice: String? = null): Boolean {
            val key = project.bashPathOrDefault
            if (runningDevice == null) {
                // don't check device
                return !isFirstTimeRun.containsKey(key)
            }

            val lastRunningDevice = isFirstTimeRun[key]
            return lastRunningDevice != runningDevice
        }

        fun setHasRun(project: Project, runningDevice: String?) {
            val key = project.bashPathOrDefault
            this.isFirstTimeRun[key] = runningDevice ?: "null"
        }

        fun resetHasRun(project: Project) {
            val key = project.bashPathOrDefault
            isFirstTimeRun.remove(key)
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
