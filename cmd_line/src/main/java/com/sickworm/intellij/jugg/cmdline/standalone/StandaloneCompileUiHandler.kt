package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.GradleOutputParser
import com.sickworm.intellij.jugg.compiler.JuggCompileStatusHolder
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Supplies non-interactive MCP confirmations, cancellation, and progress text. */
class StandaloneCompileUiHandler(
    private val options: JuggGradleCompileOptions,
    override val isSkipDeploy: Boolean,
    override val isAlwaysRestartApp: Boolean,
    override val isRpcMode: Boolean,
    override var isForceGradleCompile: Boolean,
    private val logger: Logger,
) : CompileUiHandler {
    private val canceled = AtomicBoolean()
    private var cancelListener: (() -> Unit)? = null
    @Volatile var indicatorText: String = ""
        private set

    override val isCanceled: Boolean get() = canceled.get()
    override var processHandler: IProcessHandler = StandaloneProcessHandler(canceled)
    override var progressIndicator: ProgressIndicator = DumbProgressIndicator()
    override var testEventSinkFactory: ((String, Boolean) -> ((InstrumentationEvent) -> Unit)?)? = null

    override fun createCompileStatusHolder(): CompileStatusHolder =
        JuggCompileStatusHolder(processHandler, progressIndicator, logger)
    override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener =
        GradleOutputParser(options, processHandler, progressIndicator, logger)
    override fun confirmFallbackWhenNoFileChanges() = ConfirmResult.NEGATIVE
    override fun confirmBuildChanges(changedBuildFiles: List<Pair<File, File?>>) = BuildChangesConfirmResult.FALLBACK
    override fun confirmDependencyChanges(runResult: DependencyDiffResultSet?) = ConfirmResult.POSITIVE
    override fun confirmEmbeddedToApk() = ConfirmResult.POSITIVE
    override fun updateIndicatorText(text: String) { indicatorText = text; progressIndicator.text = text }
    override fun listenCancelAction(listener: (() -> Unit)?) { cancelListener = listener; processHandler.cancelAction = listener }
    override fun notifyByBalloon(text: String) = logger.info(text)
    override fun ensureRunWindowCreated() = Unit
    override fun showRunWindow() = Unit
    override fun shouldAutoConfirmDeployPrompt(message: String): Boolean = true
    override fun onDeployUiMessage(message: String) { updateIndicatorText(message) }
    override fun cancel() {
        if (canceled.compareAndSet(false, true)) cancelListener?.invoke()
    }

    private class StandaloneProcessHandler(private val canceled: AtomicBoolean) : IProcessHandler {
        override var isCanceledByNextTask: Boolean = false
        override val isCanceled: Boolean get() = canceled.get()
        override var cancelAction: (() -> Unit)? = null
        override fun notifyTextAvailable(text: String, outputType: Key<*>) = Unit
        override fun detachProcess() { canceled.set(true); cancelAction?.invoke() }
        override fun destroyProcess() = detachProcess()
    }
}
