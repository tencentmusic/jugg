package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.getInstance

class JuggCompileUiHandler(
    override val isForceInstall: Boolean,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: IProcessHandler,
    private val indicator: ProgressIndicator,
    logger: Logger,
) : CompileUiHandler {

    private val logger = logger.getInstance("JuggCompileUiHandler")

    override val isCanceled: Boolean get() = indicator.isCanceled || processHandler.isCanceled

    override val compileStatusHolder: CompileStatusHolder
        get() = JuggCompileStatusHolder(processHandler, indicator, logger)

    override val outputParser: IGradleCompileClient.TerminalOutputListener
        get() = GradleOutputParser(juggGradleCompileOptions, processHandler, indicator, logger)

    override fun updateIndicatorText(text: String) {
        indicator.text = text
    }

    override fun listenCancelAction(listener: (() -> Unit)?) {
        processHandler.cancelAction = listener
    }

}