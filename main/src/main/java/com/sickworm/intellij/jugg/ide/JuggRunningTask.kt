package com.sickworm.intellij.jugg.ide

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
import java.io.PrintWriter
import java.io.StringWriter
import javax.swing.SwingUtilities


@Suppress("DialogTitleCapitalization")
class JuggRunningTask(
    project: Project,
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
            JuggLogger.listenProjectLog(project, loggerListener)
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
            JuggLogger.stopListenProjectLog(project, loggerListener)
        }
    }

    private fun showGreenDotOnRunToolWindow() {
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
        indicatorListener.installToProgressIfPossible(indicator)
        indicator.text = "Compiling by Jugg..."
        indicator.isIndeterminate = true
    }

    private fun doRun(indicator: ProgressIndicator, isForceGradleCompile: Boolean) {
        val compileTaskResult = compileTask(indicator, isForceGradleCompile)
        val canNotRetry = isForceGradleCompile || compileTaskResult.isGradleCompile
        if (!compileTaskResult.isSuccess) {
            return if (canNotRetry) {
                failedAndActiveRunWindow()
            } else {
                logger.warn("Compile Failed. Going to restart with fallback gradle compile.")
                doRun(indicator, true)
            }
        }

        indicator.text = "Launching app..."
        logger.info("Installing and launching app...")

        val deployTaskResult = deployTask(compileTaskResult.isGradleCompile)
        if (!deployTaskResult.isSuccess) {
            return if (canNotRetry) {
                if (compileTaskResult.isGradleCompile) {
                    initIncrementalCompileTask.invoke()
                }
                failedAndActiveRunWindow()
            } else {
                logger.warn("Deploy Failed. Going to restart with fallback gradle compile.")
                doRun(indicator, true)
            }
        }

        logger.info("\nApp launched.")
        notifyLaunched()

        if (compileTaskResult.isGradleCompile) {
            initIncrementalCompileTask.invoke()
        }
    }

    private fun notifyLaunched() {
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.notifyByBalloon("Run", MessageType.INFO, "Launch succeeded")
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
    }

    fun cancel() {
        if (!processHandler.isProcessTerminated) {
            logger.warn("Try canceling process...")
            processHandler.detachProcess()
        }
    }
}
