package com.sickworm.intellij.jugg.ide.ui

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import org.apache.log4j.Level
import java.io.OutputStream

/**
 * Implementation of ProcessHandler, handle detach and destroy process.
 */
class SimpleProcessHandler : ProcessHandler(),
    AnsiEscapeDecoder.ColoredTextAcceptor, IProcessHandler {

    private val myAnsiEscapeDecoder = AnsiEscapeDecoder()
    private val logger = Logger.getInstance("SimpleProcessHandler")

    override var cancelAction: (() -> Unit)? = null

    override val isCanceled get() = isProcessTerminating || isProcessTerminated

    override var isCanceledByNextTask = false

    override fun destroyProcessImpl() {
        logger.debug("[Jugg] destroyProcessImpl, hasCancelAction=${cancelAction != null}, " +
            "isTerminating=$isProcessTerminating, isTerminated=$isProcessTerminated, isCanceledByNextTask=$isCanceledByNextTask")
        detachProcessImpl()
    }

    override fun detachProcessImpl() {
        logger.debug("[Jugg] detachProcessImpl, hasCancelAction=${cancelAction != null}, " +
            "isTerminating=$isProcessTerminating, isTerminated=$isProcessTerminated, isCanceledByNextTask=$isCanceledByNextTask")
        cancelAction?.invoke()
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

/**
 * Usage: Listen project log and output by ProcessHandler
 */
class ProcessHandlerLoggerWrapper(
    private var processHandler: IProcessHandler,
    private val isOutputEnabled: Boolean = true,
) : Logger() {

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String) {
    }

    override fun debug(t: Throwable?) {
    }

    override fun debug(message: String, t: Throwable?) {
    }

    override fun info(message: String) {
        if (!isOutputEnabled) return
        processHandler.notifyTextAvailable("$message\n", ProcessOutputType.STDOUT)
    }

    override fun info(message: String, t: Throwable?) {
        if (!isOutputEnabled) return
        processHandler.notifyTextAvailable("$message\n", ProcessOutputType.STDOUT)
        if (t != null) {
            processHandler.notifyTextAvailable(t.toString(), ProcessOutputType.STDOUT)
            processHandler.notifyTextAvailable("\n", ProcessOutputType.STDOUT)
        }
    }

    override fun warn(message: String, t: Throwable?) {
        if (!isOutputEnabled) return
        processHandler.notifyTextAvailable("$message\n", ProcessOutputType.STDERR)
        if (t != null) {
            processHandler.notifyTextAvailable(t.toString(), ProcessOutputType.STDERR)
            processHandler.notifyTextAvailable("\n", ProcessOutputType.STDERR)
        }
    }

    override fun error(message: String, t: Throwable?, vararg details: String?) {
        if (!isOutputEnabled) return
        processHandler.notifyTextAvailable("$message\n", ProcessOutputType.STDERR)
        if (t != null) {
            processHandler.notifyTextAvailable(t.toString(), ProcessOutputType.STDERR)
            processHandler.notifyTextAvailable("\n", ProcessOutputType.STDERR)
        }
        details.forEach {
            processHandler.notifyTextAvailable(it.toString(), ProcessOutputType.STDERR)
            processHandler.notifyTextAvailable("\n", ProcessOutputType.STDERR)
        }
    }

    @Suppress("UnstableApiUsage")
    override fun setLevel(level: Level) {
    }

}
