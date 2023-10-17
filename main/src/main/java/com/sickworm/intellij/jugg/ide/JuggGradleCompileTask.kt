package com.sickworm.intellij.jugg.ide

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.logger.JuggLogger
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
) {

    fun run(): GradleCompileResult {
        return try {
            doRun()
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logger.warn("\nCompile stop unexpected with ${e::class.java}:\n$sw\n")
            logger.warn("\nCompile stop unexpected.")
            GradleCompileResult.failed(false, "Exception: $e")
        }
    }

    private fun doRun(): GradleCompileResult {
        val outputListener = GradleOutputParser(
            juggGradleCompileOptions, project.basePath ?: "",
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

        val (costTime, result) = measureTimeMillisWithResult {
            compileClient.login(juggGradleCompileOptions)
            compileClient.compileAndFetchResult()
        }

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
    private val projectRootPath: String,
    private val processHandler: ProcessHandler,
    private val indicator: ProgressIndicator,
    private val logger: Logger,
) : IGradleCompileClient.TerminalOutputListener {

    val possibleErrorLog = mutableListOf<String>()
    private var isCollectingTaskErrorMsg = false
    private var isCollectingExceptionErrorMsg = false

    override fun onOutput(line: String) {
        val parsedOutput = parseOutput(line)
        processHandler.notifyTextAvailable(parsedOutput, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)

        if (parsedOutput.startsWith("[Jugg] SyncFileCommand exec start")) {
            indicator.text = "Syncing file to remote..."
        } else if (parsedOutput.startsWith("[Jugg] CompileProjectCommand exec start")) {
            indicator.text = "Compiling project..."
        } else if (parsedOutput.startsWith("[Jugg] FetchOutputCommand exec start")) {
            indicator.text = "Getting apk..."
        } else if (parsedOutput.startsWith("> Configure project ")) {
            val projectName = parsedOutput.substring("> Configure project ".length)
            indicator.text = "Configured $projectName..."
        } else if (parsedOutput.startsWith("> Task ")) {
            val taskName = parsedOutput.substring("> Task ".length).substringBefore(" ")
            indicator.text = "Executed $taskName..."
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
        val remoteProjectAbsPath = juggGradleCompileOptions.remoteProjectPath
        return line.replace(remoteProjectAbsPath, projectRootPath)
    }
}
