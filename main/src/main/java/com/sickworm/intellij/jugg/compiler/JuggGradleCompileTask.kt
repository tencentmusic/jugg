package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.JuggException
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter


class JuggGradleCompileTask(
    private val compileClient: IGradleCompileClient,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val uiHandler: CompileUiHandler,
    private val isOnlyFetchResult: Boolean,
    private val logger: Logger,
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
        val outputParser = uiHandler.createOutputParser()
        compileClient.terminalOutputListener = outputParser
        logger.debug("[Jugg] compile cancel listener registered, client=${compileClient::class.simpleName}")
        uiHandler.listenCancelAction {
            try {
                logger.debug("[Jugg] compile cancel listener invoked, client=${compileClient::class.simpleName}, uiCanceled=${uiHandler.isCanceled}")
                compileClient.cancelAction(isByUser = false)
            } catch (e: Exception) {
                logger.warn("Cancel compile failed with ${e::class.java}", e)
            }
        }

        logger.info("\nJugg gradle compile started.\n")
        uiHandler.updateIndicatorText("Start gradle compiling...")

        val updateTimeJob = launch {
            while (isActive) {
                delay(10_000)
                outputParser.updateIndicatorWithTime()
            }
        }

        TimeLogger.start("compile")
        juggGradleCompileOptions.checkConfig()
        compileClient.login(juggGradleCompileOptions)
        val result = compileClient.compileAndFetchResult(isOnlyFetchResult)
        val costTime = TimeLogger.getCostTime("compile")
        updateTimeJob.cancel()

        val isCanceled = uiHandler.isCanceled
        if (result.isSuccess) {
            logger.info("\nBUILD SUCCESSFUL in ${costTime / 1000}s.\n")
        } else if (isCanceled) {
            logger.warn("\nBUILD CANCELED in ${costTime / 1000}s.\n")
        } else {
            if (outputParser.possibleErrorLog.isNotEmpty()) {
                logger.warn("\n[Jugg] Found error in logs:")
                outputParser.possibleErrorLog.forEach { logger.warn(it) }
            }
            logger.warn("\nBUILD FAILED in ${costTime / 1000}s.\n")
        }

        compileClient.terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT
        uiHandler.listenCancelAction(null)
        logger.debug("[Jugg] compile cancel listener cleared, client=${compileClient::class.simpleName}")
        // Attach collected error lines to the result so callers can use a compact error summary.
        return if (!result.isSuccess && !isCanceled && outputParser.possibleErrorLog.isNotEmpty()) {
            result.copy(errorLog = outputParser.possibleErrorLog.toList())
        } else {
            result
        }
    }

}
