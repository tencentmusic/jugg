package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.GradleCompileSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.io.IOException
import java.io.PrintStream

class LocalGradleCompileClient(
    private val project: Project,
    private val logger: Logger = JuggLogger.getInstance(project, "LocalClient"),
) : IGradleCompileClient {

    private var gradleCompileSettings: GradleCompileSettings? = null
    @Volatile
    private var currentRunningProcess: Process? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    override fun login(gradleCompileSettings: GradleCompileSettings) {
        // no need to login
        this.gradleCompileSettings = gradleCompileSettings
    }

    override fun compileAndFetchResult(): GradleCompileResult {
        isCanceled = false
        val clientInfo = gradleCompileSettings ?: throw JuggInternalException.notLoginYet()

        val compileProjectCommand = CompileProjectCommand(clientInfo.compileCommand, project.basePath!!)
        val compileProjectResult = invoke(compileProjectCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
            return GradleCompileResult.failed(isCanceled)
        }
        return GradleCompileResult.success(File(""))
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): Boolean {
        isCanceled = false
        // do nothing
        return true
    }

    @Volatile
    private var isCanceled = false

    override fun cancelAction() {
        currentRunningProcess?.destroy()
        isCanceled = true
    }

    private fun invoke(command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", command.command))
        currentRunningProcess = process
        val commander = PrintStream(process.outputStream, true)
        commander.println(command.command)
        commander.flush()

        val errorPrintThread = object : Thread() {
            override fun run() {
                val reader = process.errorStream.bufferedReader(Charsets.UTF_8)
                while (!isInterrupted) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            if (line.isNotEmpty()) {
                                printToStreamError(line)
                            }
                        }
                    } catch (e: IOException) {
                        // java.io.IOException: Stream closed
                        break
                    }
                }
            }
        }
        errorPrintThread.start()

        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var result: Int
        while (true) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    if (line.isNotEmpty()) {
                        printToStream(line)
                    }
                    val output = command.getInput(line)
                    if (output != null) {
                        commander.println(output)
                        commander.flush()
                    }
                    val currentResult = command.hasFinishWithResult(line)
                    if (currentResult != null) {
                        result = currentResult
                        break
                    }
                }
            } catch (e: IOException) {
                // java.io.IOException: Stream closed
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }

            if (!process.isAlive) {
                printToStream("[Jugg] exit-status: " + process.exitValue())
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }
        }
        process.waitFor()
        errorPrintThread.interrupt()
        currentRunningProcess = null

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun printToStream(line: String) {
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamInfo(line: String) {
        logger.info(line)
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        logger.warn(line, e)
        terminalOutputListener.onOutputErr(line)
        e?.let {
            terminalOutputListener.onOutputErr(e.toString())
        }
    }

    private fun printToStreamErrorIfCanceled(line: String, e: Exception? = null) {
        if (isCanceled) {
            return
        }
        return printToStreamError(line, e)
    }

    override fun dispose() {
        currentRunningProcess?.destroy()
    }

}
