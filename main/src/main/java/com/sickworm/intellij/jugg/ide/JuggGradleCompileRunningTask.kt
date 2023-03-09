package com.sickworm.intellij.jugg.ide

import com.android.ddmlib.IDevice
import com.intellij.execution.configurations.*
import com.intellij.execution.process.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.*
import com.intellij.openapi.progress.util.ProgressIndicatorListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.aapt2.ApkReader
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.apache.log4j.Level
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter

import java.io.StringWriter




@Suppress("DialogTitleCapitalization")
class JuggGradleCompileRunningTask(
    project: Project,
    private val compileClient: IGradleCompileClient,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: ProcessHandler,
    private val deviceGetter: () -> IDevice,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggGradleCompileRunningTask")
) : Task.Backgroundable(project, "Running Jugg") {


    override fun run(indicator: ProgressIndicator) {
        try {
            doRun(indicator)
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logger.error("run failed", e)
            processHandler.notifyTextAvailable("\nCompile stop unexpected with exception: $sw\n", ProcessOutputType.STDERR)
        } finally {
            stop(indicator)
        }
    }

    private fun doRun(indicator: ProgressIndicator) {
        compileClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
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
                logger.warn("onOutputErr: $line")
                processHandler.notifyTextAvailable(parsedOutput, ProcessOutputType.STDERR)
                processHandler.notifyTextAvailable("\n", ProcessOutputType.STDERR)
            }
        }
        object : ProgressIndicatorListener {
            override fun cancelled() { processHandler.detachProcess() }
            override fun stopped() { }
        }.installToProgressIfPossible(indicator)

        processHandler.startNotify()
        processHandler.notifyTextAvailable("Jugg compile started.\n", ProcessOutputType.STDOUT)
        indicator.text = "Compiling by Jugg..."
        indicator.isIndeterminate = true


        val (costTime, result) = measureTimeMillisWithResult {
            compileClient.login(juggGradleCompileOptions)
            compileClient.compileAndFetchResult()
        }

        val isCanceled = indicator.isCanceled || processHandler.isProcessTerminated
        if (result.isSuccess) {
            processHandler.notifyTextAvailable("\n\nBUILD SUCCESS in ${costTime / 1000}s.\n\n", ProcessOutputType.STDOUT)
        } else if (isCanceled) {
            processHandler.notifyTextAvailable("\n\nBUILD CANCELED in ${costTime / 1000}s.\n\n", ProcessOutputType.STDERR)
        } else {
            processHandler.notifyTextAvailable("\n\nBUILD FAILED in ${costTime / 1000}s.\n\n", ProcessOutputType.STDERR)
        }

        if (result.isSuccess) {
            indicator.text = "Launching app..."
            processHandler.notifyTextAvailable("Installing and launching app...\n", ProcessOutputType.STDOUT)
            if (installAndLaunch(result.compileOutputFile)) {
                processHandler.notifyTextAvailable("\nApp launched.\n", ProcessOutputType.STDOUT)
            }
        }
    }

    private fun stop(indicator: ProgressIndicator) {
        indicator.stop()
        if (!processHandler.isProcessTerminated) {
            processHandler.detachProcess()
        }
    }

    private fun installAndLaunch(apkFile: File): Boolean {
        val loggerWrapper = LoggerWrapper(processHandler, logger)

        val apkReader = ApkReader(apkFile, loggerWrapper)
        val packageName = apkReader.readPackageNameFast()
        val apkProvider = ApkReader(apkFile, loggerWrapper).toApkProvider(packageName)
        val device = try {
            deviceGetter.invoke()
        } catch (e: Exception) {
            processHandler.notifyTextAvailable("No available device. Skip install.\n", ProcessOutputType.STDERR)
            return false
        }

        processHandler.notifyTextAvailable("Installing APK... $apkFile\n", ProcessOutputType.STDOUT)
        AdbCmdHelper(device, loggerWrapper).let {
            val (isSuccess, reason) = it.installApp(apkFile)
            if (!isSuccess) {
                processHandler.notifyTextAvailable("\n\nInstall APK failed. $reason\n", ProcessOutputType.STDERR)
                return false
            }
            it.startDefaultApp(packageName, apkProvider)
        }

        return true
    }

    private fun parseOutput(line: String): String {
        return replacePathIfNeeded(line)
    }

    private fun replacePathIfNeeded(line: String): String {
        if (!juggGradleCompileOptions.isRemoteCompile) {
            return line
        }
        val remoteProjectAbsPath = juggGradleCompileOptions.remoteProjectPath
        val basePath = project.basePath ?: return line
        return line.replace(remoteProjectAbsPath, basePath)
    }

}

class SimpleProcessHandler(private val cancelAction: () -> Unit) : ProcessHandler(), AnsiEscapeDecoder.ColoredTextAcceptor {

    private val myAnsiEscapeDecoder = AnsiEscapeDecoder()

    override fun destroyProcessImpl() {
        detachProcessImpl()
    }

    override fun detachProcessImpl() {
        cancelAction.invoke()
        notifyProcessTerminated(0)
    }

    override fun detachIsDefault() = true

    override fun waitFor() = true

    override fun waitFor(timeoutInMilliseconds: Long) = true

    override fun getProcessInput(): OutputStream? {
        return null
    }

    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        myAnsiEscapeDecoder.escapeText(text, outputType, this)
    }

    override fun coloredTextAvailable(text: String, attributes: Key<*>) {
        super.notifyTextAvailable(text, attributes)
    }
}

private class LoggerWrapper(private val processHandler: ProcessHandler, private val logger: Logger): Logger() {

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String) {
        logger.debug(message)
        processHandler.notifyTextAvailable(message, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)
    }

    override fun debug(t: Throwable?) {
        logger.debug(t)
    }

    override fun debug(message: String?, t: Throwable?) {
        logger.debug(message, t)
    }

    override fun info(message: String) {
        logger.info(message)
        processHandler.notifyTextAvailable(message, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)
    }

    override fun info(message: String?, t: Throwable?) {
        logger.info(message, t)
    }

    override fun warn(message: String, t: Throwable?) {
        logger.warn(message, t)
        processHandler.notifyTextAvailable(message, ProcessOutputType.STDERR)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDERR)
    }

    override fun error(message: String, t: Throwable?, vararg details: String?) {
        logger.error(message, t, *details)
        processHandler.notifyTextAvailable(message, ProcessOutputType.STDERR)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDERR)
    }

    @Suppress("UnstableApiUsage")
    override fun setLevel(level: Level) {
    }
}
