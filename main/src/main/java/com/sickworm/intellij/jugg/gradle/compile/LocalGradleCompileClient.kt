package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.io.IOException
import java.io.PrintStream

class LocalGradleCompileClient(
    private val project: Project,
    private val logger: Logger = JuggLogger.getInstance(project, "LocalClient"),
) : IGradleCompileClient {

    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null
    @Volatile
    private var currentRunningProcess: Process? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        // no need to login
        this.juggGradleCompileOptions = juggGradleCompileOptions
    }

    override fun compileAndFetchResult(): GradleCompileResult {
        isCanceled = false
        val juggGradleCompileOptions = juggGradleCompileOptions ?: throw JuggInternalException.notLoginYet()

        val compileProjectCommand = CompileProjectCommand(juggGradleCompileOptions.compileCommand, project.basePath!!)
        val compileProjectResult = invoke(compileProjectCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
            return GradleCompileResult.failed(isCanceled, "Compile project failed $compileProjectResult")
        }

        // try sub dir first
        val subDir = File(project.basePath!!, "app/build/outputs")
        var apkFile = subDir.findFilesRecursively(juggGradleCompileOptions.outputApkName)
        if (apkFile == null) {
            // find in root dir
            val rootDir = File(project.basePath!!)
            apkFile = rootDir.findFilesRecursively(juggGradleCompileOptions.outputApkName)
        }
        if (apkFile == null) {
            printToStreamError("Can't find apk \"${juggGradleCompileOptions.outputApkName}\" " +
                    "in ${project.basePath}, please make sure your run configuration is right.")
            return GradleCompileResult.failed(isCanceled, "Can't find apk")
        }
        return GradleCompileResult.success(apkFile)
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File {
        isCanceled = false
        return File(project.basePath!!)
    }

    @Volatile
    private var isCanceled = false

    override fun cancelAction(isByUser: Boolean) {
        if (isByUser) {
            printToStreamInfo("[Jugg] user cancel")
        }
        currentRunningProcess?.destroy()
        isCanceled = true
    }

    private fun invoke(command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", command.getCommend(isNeedSetChineseLanguage = false)))
        currentRunningProcess = process

        command.beforeInvokeCommand()
        val commander = PrintStream(process.outputStream, false)

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
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        logger.warn(line, e)
    }

    private fun printToStreamErrorIfCanceled(line: String, e: Exception? = null) {
        if (isCanceled) {
            return
        }
        return printToStreamError(line, e)
    }

    override fun dispose() {
        currentRunningProcess?.destroy()
        currentRunningProcess = null
    }

}
