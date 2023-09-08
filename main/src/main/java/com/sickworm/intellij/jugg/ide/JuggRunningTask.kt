package com.sickworm.intellij.jugg.ide

import com.google.gson.Gson
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressIndicatorListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.CompileTaskResult
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.JuggReporter
import java.io.PrintWriter
import java.io.StringWriter
import javax.swing.SwingUtilities


@Suppress("DialogTitleCapitalization")
class JuggRunningTask(
    private val project: Project,
    private val juggReporter: JuggReporter,
    private val processHandler: ProcessHandler,
    private val compileTask: (indicator: ProgressIndicator, forceFullCompile: Boolean) -> CompileTaskResult,
    private val deployTask: (forceInstall: Boolean) -> DeployTaskResult,
    private val initIncrementalCompileTask: () -> Unit,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggRunningTask"),
) : Task.Backgroundable(project, "Running Jugg") {

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
            stop(indicator)
            isRunning = false
            isFirstTimeRun = false
            JuggLogger.stopListenProjectLog(project, loggerListener)
        }
    }

    private fun showGreenDotOnRunToolWindow() {
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.getToolWindow("Run")?.let {
                val icon = ExecutionUtil.getLiveIndicator(it.icon)
                if (isFirstTimeRun) {
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
            failedAndActiveRunWindow()
            return
        }

        if (compileTaskResult.isGradleCompile) {
            logger.info("Launching app...")
            indicator.text = "Launching app..."
        } else {
            logger.info("Deploying changes...")
            indicator.text = "Deploying changes..."
        }

        val deployTaskResult = deployTask(compileTaskResult.isGradleCompile)
        detailMap["deploy_failed_reason"] = deployTaskResult.failedReason ?: ""
        detailMap["deploy_type"] = deployTaskResult.deployType ?: ""
        juggReporter.report {
            action = "deploy"
            isSuccess = deployTaskResult.isSuccess
            costTime = deployTaskResult.costTime
            detail = Gson().toJson(detailMap)
        }

        val canNotRetry = compileTaskResult.isGradleCompile
        if (!deployTaskResult.isSuccess) {
            return if (canNotRetry) {
                initIncrementalCompileTask.invoke()
                failedAndActiveRunWindow()
            } else {
                logger.warn("Deploy Failed. Going to restart with fallback gradle compile.")
                doRun(indicator, true)
            }
        }

        logger.info("\nApp launched.")
        notifyLaunched(compileTaskResult.isGradleCompile)

        if (compileTaskResult.isGradleCompile) {
            initIncrementalCompileTask.invoke()
        }
    }

    private fun notifyLaunched(isGradleCompile: Boolean) {
        val text = if (isGradleCompile) {
            "Launch succeeded"
        } else {
            "Deploy changes succeeded"
        }
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.notifyByBalloon("Run", MessageType.INFO, text)
        }
    }

    private fun failedAndActiveRunWindow() {
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
            processHandler.detachProcess()
        } else {
            logger.debug("Process already terminated.")
            onFinishListener.invoke()
        }
    }

    companion object {
        var isFirstTimeRun = true
            private set
    }
}
