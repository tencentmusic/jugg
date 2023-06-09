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
    private val logger: Logger = JuggLogger.getInstance(project, "JuggGradleCompileRunningTask"),
) {

    private val outputListener = GradleOutputParser(
        juggGradleCompileOptions,
        project.basePath ?: "",
        processHandler,
        indicator,
        logger,
    )

    fun run(): GradleCompileResult {
        return try {
            doRun()
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logger.warn("\nCompile stop unexpected with ${e::class.java}:\n$sw\n")
            logger.warn("\nCompile stop unexpected.")
            GradleCompileResult.failed(false)
        }
    }

    private fun doRun(): GradleCompileResult {
        compileClient.terminalOutputListener = outputListener
        processHandler.cancelAction = {
            compileClient.cancelAction(isByUser = false)
        }

        logger.info("\nJugg gradle compile started.\n")

        val (costTime, result) = measureTimeMillisWithResult {
            compileClient.login(juggGradleCompileOptions)
            compileClient.compileAndFetchResult()
        }

        val isCanceled = indicator.isCanceled || processHandler.isProcessTerminated
        if (result.isSuccess) {
            logger.info("\nBUILD SUCCESS in ${costTime / 1000}s.\n")
        } else if (isCanceled) {
            logger.warn("BUILD CANCELED in ${costTime / 1000}s.\n")
        } else {
            logger.warn("BUILD FAILED in ${costTime / 1000}s.\n")
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

    override fun onOutput(line: String) {
        val parsedOutput = parseOutput(line)
        processHandler.notifyTextAvailable(parsedOutput, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)

        if (line.startsWith("[Jugg] SyncFileCommand exec start")) {
            indicator.text = "Syncing file to remote..."
        } else if (line.startsWith("[Jugg] CompileProjectCommand exec start")) {
            indicator.text = "Compiling project..."
        } else if (line.startsWith("[Jugg] FetchOutputCommand exec start")) {
            indicator.text = "Getting apk..."
        } else if (line.startsWith("> Configure project ")) {
            val projectName = line.substring("> Configure project ".length)
            indicator.text = "Configured $projectName..."
        } else if (line.startsWith("> Task ")) {
            val taskName = line.substring("> Task ".length).substringBefore(" ")
            indicator.text = "Executed $taskName..."
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
