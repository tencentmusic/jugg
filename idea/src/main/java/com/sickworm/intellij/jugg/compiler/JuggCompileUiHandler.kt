package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.ide.ui.BuildChangesConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import java.io.File
import javax.swing.SwingUtilities

open class JuggCompileUiHandler(
    private val project: Project,
    override var isForceGradleCompile: Boolean,
    private val isRpcMode: Boolean,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    logger: Logger,
    override var processHandler: IProcessHandler = IProcessHandler.DEFAULT,
    override var progressIndicator: ProgressIndicator = DumbProgressIndicator.INSTANCE,
    override var testEventSinkFactory: ((String) -> ((InstrumentationEvent) -> Unit)?)? = null,
    override val isSkipDeploy: Boolean = false,
    override val isAlwaysRestartApp: Boolean = false,
) : CompileUiHandler {

    private val logger = logger.getInstance("JuggCompileUiHandler")

    override val isCanceled: Boolean get() = progressIndicator.isCanceled || processHandler.isCanceled

    override fun createCompileStatusHolder(): CompileStatusHolder {
        return JuggCompileStatusHolder(processHandler, progressIndicator, logger)
    }

    override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener {
        return GradleOutputParser(juggGradleCompileOptions, processHandler, progressIndicator, logger)
    }

    override fun confirmFallbackWhenNoFileChanges(): ConfirmResult {
        if (isRpcMode) {
            return ConfirmResult.NEGATIVE
        }
        return CommonConfirmDialog.showAndGetOrCancel(
            title = "Confirm Fallback to Gradle",
            content = "No file changes, do you want to fallback to gradle?",
            okButtonText = "Fallback to Gradle",
            negativeButtonText = "Don't fallback",
            leftButtonText = "Cancel",
        )
    }

    override fun confirmBuildChanges(
        project: Project,
        changedBuildFiles: List<Pair<File, File?>>
    ): BuildChangesConfirmResult {
        if (isRpcMode) {
            return BuildChangesConfirmResult.FALLBACK
        }
        return BuildChangesConfirmDialog.showAndGetResult(project, changedBuildFiles)
    }

    override fun confirmDependencyChanges(dependencyChangeManager: IDependencyChangeManager, runResult: DependencyDiffResultSet?): ConfirmResult {
        if (isRpcMode) {
            return ConfirmResult.POSITIVE
        }
        return dependencyChangeManager.tryShowChangeConfirmDialog(runResult)
    }

    override fun updateIndicatorText(text: String) {
        progressIndicator.text = text
    }

    override fun listenCancelAction(listener: (() -> Unit)?) {
        processHandler.cancelAction = listener
    }

    override fun notifyByBalloon(text: String) {
        JuggRunningTask.notifyByBalloon(project, text)
    }

    override fun showRunWindow() {
        if (isRpcMode) {
            return
        }
        SwingUtilities.invokeLater {
            val toolWindowManager: ToolWindowManager = ToolWindowManager.getInstance(project)
            toolWindowManager.getToolWindow("Run")?.activate(null)
        }
    }

    override fun shouldAutoConfirmDeployPrompt(message: String): Boolean {
        if (isRpcMode) {
            logger.warn("The device already has an application with the same package but a different signature.")
            logger.warn("Uninstall and reinstall directly for in MCP mode.")
            return true
        }
        return false
    }

    override fun onDeployUiMessage(message: String) {
        if (isRpcMode) {
            logger.debug("MCP deploy ui message: $message")
        }
    }

    override fun cancel() {
        return processHandler.detachProcess()
    }
}