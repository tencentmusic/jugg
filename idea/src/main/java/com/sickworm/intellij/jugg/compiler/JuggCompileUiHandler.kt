package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.ui.BuildChangesConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import java.io.File

class JuggCompileUiHandler(
    override val isForceInstall: Boolean,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    private val processHandler: IProcessHandler,
    private val indicator: ProgressIndicator,
    logger: Logger,
) : CompileUiHandler {

    private val logger = logger.getInstance("JuggCompileUiHandler")

    override val isCanceled: Boolean get() = indicator.isCanceled || processHandler.isCanceled

    override fun createCompileStatusHolder(): CompileStatusHolder {
        return JuggCompileStatusHolder(processHandler, indicator, logger)
    }

    override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener {
        return GradleOutputParser(juggGradleCompileOptions, processHandler, indicator, logger)
    }

    override fun confirmFallbackWhenNoFileChanges(): ConfirmResult {
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
        return BuildChangesConfirmDialog.showAndGetResult(project, changedBuildFiles)
    }

    override fun confirmDependencyChanges(dependencyChangeManager: IDependencyChangeManager, runResult: DependencyDiffResultSet?): ConfirmResult {
        return dependencyChangeManager.tryShowChangeConfirmDialog(runResult)
    }

    override fun updateIndicatorText(text: String) {
        indicator.text = text
    }

    override fun listenCancelAction(listener: (() -> Unit)?) {
        processHandler.cancelAction = listener
    }

    override fun cancel() {
        return processHandler.detachProcess()
    }
}