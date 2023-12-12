package com.sickworm.intellij.jugg.ide

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import kotlinx.coroutines.*
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.PrintWriter
import java.io.StringWriter


class JuggGradleCompileTask(
    private val project: Project,
    private val compileClient: IGradleCompileClient,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: SimpleProcessHandler,
    private val indicator: ProgressIndicator,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggGradleCompileTask"),
): CoroutineScope by CoroutineScope(Dispatchers.IO) {

    fun run(): GradleCompileResult {
        return try {
            doRun()
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            return if (e is JuggException) {
                logger.warn("\nCompile failed:\n${e.message}")
                logger.debug("\nCompile failed: $sw\n")
                GradleCompileResult.failed(false, e.message ?: "unknown error")
            } else {
                logger.warn("\nCompile stop unexpected with exception: ${e::class.java}:\n$sw\n")
                logger.warn("\nCompile stop unexpected.")
                GradleCompileResult.failed(false, "Exception: $e")
            }
        }
    }

    private fun doRun(): GradleCompileResult {
        val outputListener = GradleOutputParser(
            juggGradleCompileOptions,
            processHandler, indicator, logger,
        )

        compileClient.terminalOutputListener = outputListener
        processHandler.cancelAction = {
            try {
                compileClient.cancelAction(isByUser = false)
            } catch (e: Exception) {
                logger.warn("Cancel compile failed with ${e::class.java}", e)
            }
        }

        logger.info("\nJugg gradle compile started.\n")
        indicator.text = "Start gradle compiling..."

        val updateTimeJob = launch {
            while (isActive) {
                delay(10_000)
                outputListener.updateIndicatorWithTime()
            }
        }

        val (costTime, result) = measureTimeMillisWithResult {
            juggGradleCompileOptions.checkConfig()
            compileClient.login(juggGradleCompileOptions)
            compileClient.compileAndFetchResult()
        }
        updateTimeJob.cancel()

        val isCanceled = indicator.isCanceled || processHandler.isProcessTerminated
        if (result.isSuccess) {
            logger.info("\nBUILD SUCCESSFUL in ${costTime / 1000}s.\n")
        } else if (isCanceled) {
            logger.warn("\nBUILD CANCELED in ${costTime / 1000}s.\n")
        } else {
            if (outputListener.possibleErrorLog.isNotEmpty()) {
                logger.warn("\n[Jugg] Found error in logs:")
                outputListener.possibleErrorLog.forEach { logger.warn(it) }
            }
            logger.warn("\nBUILD FAILED in ${costTime / 1000}s.\n")
        }

        compileClient.terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT
        processHandler.cancelAction = null
        return result
    }

}

private class GradleOutputParser(
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: ProcessHandler,
    private val indicator: ProgressIndicator,
    private val logger: Logger,
) : IGradleCompileClient.TerminalOutputListener {

    val possibleErrorLog = mutableListOf<String>()
    private var isCollectingTaskErrorMsg = false
    private var isCollectingExceptionErrorMsg = false

    private var startCompileTime = System.currentTimeMillis()
    private var currentIndicatorText = ""

    override fun onOutput(line: String) {
        val parsedOutput = parseOutput(line)
        processHandler.notifyTextAvailable(parsedOutput, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)

        val isConfiguring = parsedOutput.startsWith("> Configure project ")
        val isExecuting = parsedOutput.startsWith("> Task ")
        if (isConfiguring || isExecuting) {
            @Suppress("KotlinConstantConditions")
            if (isConfiguring) {
                val projectName = parsedOutput.substring("> Configure project ".length)
                currentIndicatorText = "Configured $projectName..."
            } else if (isExecuting) {
                val taskName = parsedOutput.substring("> Task ".length).substringBefore(" ")
                currentIndicatorText = "Executed $taskName..."
            }
            updateIndicatorWithTime()
        }

        if (parsedOutput.startsWith("* What went wrong")) {
            isCollectingExceptionErrorMsg = true
            isCollectingTaskErrorMsg = false
        }
        if (isCollectingExceptionErrorMsg) {
            if (parsedOutput.startsWith("* Try") || parsedOutput.startsWith("===")) {
                isCollectingExceptionErrorMsg = false
            } else {
                possibleErrorLog.add(parsedOutput)
            }
        }

        if (parsedOutput.startsWith("> Task")) {
            isCollectingTaskErrorMsg = parsedOutput.contains("FAILED")
        }
        if (isCollectingTaskErrorMsg) {
            possibleErrorLog.add(parsedOutput)
        }
    }

    fun updateIndicatorWithTime() {
        val costTime = (System.currentTimeMillis() - startCompileTime) / 1000 / 60
        var timeSuffix = ""
        if (costTime >= 1) {
            timeSuffix = "(run ${costTime}min)"
        }
        indicator.text = currentIndicatorText + timeSuffix
    }

    override fun onOutputErr(line: String) {
        val parsedOutput = parseOutput(line)
        logger.warn(parsedOutput)
    }

    private fun parseOutput(line: String): String {
        return replacePathIfNeeded(line)
    }

    private fun replacePathIfNeeded(line: String): String {
        if (!juggGradleCompileOptions.isRemoteCompile) {
            return line
        }
        val remoteRootPath = juggGradleCompileOptions.finalRemoteSyncPath
        val localRootPath = juggGradleCompileOptions.localToRemoteSyncPath
        return line.replace(remoteRootPath, localRootPath)
    }
}
