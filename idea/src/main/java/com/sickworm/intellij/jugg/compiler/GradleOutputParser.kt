package com.sickworm.intellij.jugg.compiler

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import java.io.File

class GradleOutputParser(
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: IProcessHandler,
    private val indicator: ProgressIndicator,
    private val logger: Logger,
) : IGradleCompileClient.TerminalOutputListener {

    override val possibleErrorLog = mutableListOf<String>()
    private var isCollectingTaskErrorMsg = false
    private var isCollectingExceptionErrorMsg = false

    private var startCompileTime = System.currentTimeMillis()
    private var currentIndicatorText = ""

    override fun onOutput(line: String, isNeedPrint: Boolean) {

        val parsedOutput = parseOutput(line)
        if (isNeedPrint) {
            processHandler.notifyTextAvailable(parsedOutput, ProcessOutputType.STDOUT)
            processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)
        }

        if (parsedOutput.startsWith("[Jugg] SyncFileCommand exec start")) {
            updateIndicatorWithTime("Syncing files to remote...")
        } else if (parsedOutput.startsWith("[Jugg] CompileProjectCommand exec start")) {
            updateIndicatorWithTime("Compiling project...")
        } else if (parsedOutput.startsWith("[Jugg] FetchOutputCommand exec start")) {
            updateIndicatorWithTime("Getting apk...")
        } else if (parsedOutput.startsWith("> Configure project ")) {
            val projectName = parsedOutput.substring("> Configure project ".length)
            updateIndicatorWithTime("Configured $projectName...")
        } else if (parsedOutput.startsWith("> Task ")) {
            val taskName = parsedOutput.substring("> Task ".length).substringBefore(" ")
            updateIndicatorWithTime("Executed $taskName...")
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
        } else if (parsedOutput.startsWith("e:")) {
            // Kotlin compile error, which may not output in order
            possibleErrorLog.add(parsedOutput)
        }
    }

    override fun updateIndicatorWithTime(newText: String?) {
        if (newText != null) {
            currentIndicatorText = newText
        }
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
        val localRootPath = File(juggGradleCompileOptions.projectRootPath).parentFile.path
        return line.replace(remoteRootPath, localRootPath)
    }
}