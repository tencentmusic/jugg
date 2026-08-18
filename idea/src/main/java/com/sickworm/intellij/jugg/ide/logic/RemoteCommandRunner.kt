package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs one remote command without entering the compile and deploy task pipeline. */
class RemoteCommandRunner(
    private val project: Project,
    logger: Logger,
) {

    private val logger = logger.getInstance("RemoteCommandRunner")

    fun run(configurationName: String, options: JuggGradleCompileOptions, command: String) {
        val client = RemoteGradleCompileClient(project, logger = logger.getInstance("RemoteClient"))
        val processHandler = RemoteCommandProcessHandler { client.cancelAction(true) }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(processHandler)
        processHandler.startNotify()
        client.terminalOutputListener = createOutputListener(processHandler)

        val executionResult = DefaultExecutionResult(console, processHandler)
        val descriptor = RunContentDescriptor(
            executionResult.executionConsole,
            executionResult.processHandler,
            executionResult.executionConsole.component,
            "Jugg Remote Command [$configurationName]",
        )
        RunContentManager.getInstance(project).showRunContent(
            DefaultRunExecutor.getRunExecutorInstance(),
            descriptor,
        )
        printHeader(processHandler, options, command)

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                client.executeRemoteCommand(options, command)
            } catch (e: Throwable) {
                logger.warn("Remote command execution failed", e)
                processHandler.notifyTextAvailable(
                    "Remote command failed: ${e.message ?: e.javaClass.simpleName}\n",
                    ProcessOutputType.STDERR,
                )
                IGradleCompileClient.Error.ERROR_FAILED
            } finally {
                try {
                    client.dispose()
                } catch (e: Exception) {
                    logger.debug("Failed to dispose remote command client", e)
                }
            }
            finish(processHandler, result)
        }
    }

    private fun createOutputListener(processHandler: RemoteCommandProcessHandler): IGradleCompileClient.TerminalOutputListener {
        return object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (isNeedPrint) {
                    processHandler.notifyTextAvailable("$line\n", ProcessOutputType.STDOUT)
                }
            }

            override fun onOutputErr(line: String) {
                processHandler.notifyTextAvailable("$line\n", ProcessOutputType.STDERR)
            }
        }
    }

    private fun printHeader(
        processHandler: RemoteCommandProcessHandler,
        options: JuggGradleCompileOptions,
        command: String,
    ) {
        val target = "${options.remoteSshUser}@${options.remoteSshIp}:${options.remoteSshPort}"
        processHandler.notifyTextAvailable("Target: $target\n", ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("Working directory: ${options.remoteProjectPath}\n", ProcessOutputType.STDOUT)
        processHandler.notifyTextAvailable("Command:\n$command\n\n", ProcessOutputType.STDOUT)
    }

    private fun finish(processHandler: RemoteCommandProcessHandler, result: Int) {
        when {
            result == IGradleCompileClient.Error.ERROR_CANCELED -> {
                processHandler.notifyTextAvailable("\nRemote command canceled.\n", ProcessOutputType.STDERR)
            }
            result == IGradleCompileClient.Error.SUCCESS -> {
                processHandler.notifyTextAvailable("\nProcess finished with exit code 0.\n", ProcessOutputType.STDOUT)
            }
            result > 0 -> {
                processHandler.notifyTextAvailable("\nProcess finished with exit code $result.\n", ProcessOutputType.STDERR)
            }
            else -> processHandler.notifyTextAvailable("\nRemote command failed.\n", ProcessOutputType.STDERR)
        }
        processHandler.complete(if (result >= 0) result else 1)
    }
}

/** Keeps the Run Content active until remote cancellation is confirmed by the worker. */
internal class RemoteCommandProcessHandler(
    private val cancelAction: () -> Unit,
) : ProcessHandler(), AnsiEscapeDecoder.ColoredTextAcceptor {

    private val ansiEscapeDecoder = AnsiEscapeDecoder()
    private val terminationLatch = CountDownLatch(1)

    override fun destroyProcessImpl() = requestCancel()

    override fun detachProcessImpl() = requestCancel()

    override fun detachIsDefault() = true

    override fun waitFor(): Boolean {
        terminationLatch.await()
        return true
    }

    override fun waitFor(timeoutInMilliseconds: Long): Boolean {
        return terminationLatch.await(timeoutInMilliseconds, TimeUnit.MILLISECONDS)
    }

    override fun getProcessInput(): OutputStream? = null

    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        ansiEscapeDecoder.escapeText(text, outputType, this)
    }

    override fun coloredTextAvailable(text: String, attributes: Key<*>) {
        super.notifyTextAvailable(text, attributes)
    }

    @Synchronized
    fun complete(exitCode: Int) {
        if (!isProcessTerminated) {
            notifyProcessTerminated(exitCode)
            terminationLatch.countDown()
        }
    }

    private fun requestCancel() {
        cancelAction()
    }
}
