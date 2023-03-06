package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.*
import com.intellij.execution.process.*
import com.intellij.openapi.progress.*
import com.intellij.openapi.progress.util.ProgressIndicatorListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.OutputStream

@Suppress("DialogTitleCapitalization")
class JuggGradleCompileRunningTask(
    project: Project,
    private val remoteClient: IGradleCompileClient,
    private val gradleCompileSettings: GradleCompileSettings,
    private val processHandler: ProcessHandler,
) : Task.Backgroundable(project, "Running Jugg") {

    override fun run(indicator: ProgressIndicator) {
        remoteClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
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


        val (costTime, isSuccess) = measureTimeMillisWithResult {
            doRun()
        }

        val isCanceled = indicator.isCanceled || processHandler.isProcessTerminated
        if (isSuccess) {
            processHandler.notifyTextAvailable("\n\nBUILD SUCCESS in ${costTime / 1000}s.\n", ProcessOutputType.STDOUT)
        } else if (isCanceled) {
            processHandler.notifyTextAvailable("\n\nBUILD CANCELED in ${costTime / 1000}s.\n", ProcessOutputType.STDERR)
        } else {
            processHandler.notifyTextAvailable("\n\nBUILD FAILED in ${costTime / 1000}s.\n", ProcessOutputType.STDERR)
        }

        indicator.stop()
    }

    private fun doRun(): Boolean {
        if (!remoteCompile()) {
            return false
        }

        return true
    }

    private fun remoteCompile(): Boolean {
        remoteClient.login(gradleCompileSettings)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        return remoteCompileResult.isSuccess
    }

    private fun parseOutput(line: String): String {
        return replacePathIfNeeded(line)
    }

    private fun replacePathIfNeeded(line: String): String {
        if (!gradleCompileSettings.isRemoteCompile) {
            return line
        }
        val remoteProjectAbsPath = gradleCompileSettings.remoteClientInfo.remoteProjectPath
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
