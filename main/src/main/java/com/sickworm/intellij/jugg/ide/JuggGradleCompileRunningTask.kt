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
import org.apache.log4j.Level
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.io.OutputStream

@Suppress("DialogTitleCapitalization")
class JuggGradleCompileRunningTask(
    project: Project,
    private val compileClient: IGradleCompileClient,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: ProcessHandler,
    private val deviceGetter: () -> IDevice,
) : Task.Backgroundable(project, "Running Jugg") {

    override fun run(indicator: ProgressIndicator) {
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

        indicator.stop()
        if (!processHandler.isProcessTerminated) {
            processHandler.detachProcess()
        }
    }

    private fun installAndLaunch(apkFile: File): Boolean {
        val logger = LoggerWrapper(processHandler)

        val apkReader = ApkReader(apkFile, logger)
        val packageName = apkReader.readPackageNameFast()
        val apkProvider = ApkReader(apkFile, logger).toApkProvider(packageName)
        val device = try {
            deviceGetter.invoke()
        } catch (e: Exception) {
            processHandler.notifyTextAvailable("No available device. Skip install.\n", ProcessOutputType.STDERR)
            return false
        }

        processHandler.notifyTextAvailable("Installing APK... $apkFile\n", ProcessOutputType.STDOUT)
        AdbCmdHelper(device, logger).let {
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

private class LoggerWrapper(private val processHandler: ProcessHandler): Logger() {

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String) {
        processHandler.notifyTextAvailable(message, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)
    }

    override fun debug(t: Throwable?) {
    }

    override fun debug(message: String?, t: Throwable?) {
    }

    override fun info(message: String) {
        processHandler.notifyTextAvailable(message, ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)
    }

    override fun info(message: String?, t: Throwable?) {
    }

    override fun warn(message: String?, t: Throwable?) {
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
    }

    @Suppress("UnstableApiUsage")
    override fun setLevel(level: Level) {
    }
}
