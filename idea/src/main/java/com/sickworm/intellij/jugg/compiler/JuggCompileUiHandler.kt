package com.sickworm.intellij.jugg.compiler

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.compiler.ui.TooManyChangesConfirmResult
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.ide.ui.BuildChangesConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.TooManyChangesConfirmDialog
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import java.io.File
import javax.swing.SwingUtilities

open class JuggCompileUiHandler(
    private val project: Project,
    override var isForceGradleCompile: Boolean,
    override val isRpcMode: Boolean,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
    logger: Logger,
    override var processHandler: IProcessHandler = IProcessHandler.DEFAULT,
    override var progressIndicator: ProgressIndicator = DumbProgressIndicator.INSTANCE,
    override var testEventSinkFactory: ((String, Boolean) -> ((InstrumentationEvent) -> Unit)?)? = null,
    override val isSkipDeploy: Boolean = false,
    override val isAlwaysRestartApp: Boolean = false,
    override val isDebugRun: Boolean = false,
    isGradleCacheRefreshRequested: Boolean = false,
    private val onEndListener: ((RunResult) -> Unit)? = null,
) : CompileUiHandler {

    private val logger = logger.getInstance("JuggCompileUiHandler")
    final override var isGradleCacheRefreshRequested: Boolean = isGradleCacheRefreshRequested
        private set

    override val isCanceled: Boolean get() = progressIndicator.isCanceled || processHandler.isCanceled

    override fun createCompileStatusHolder(): CompileStatusHolder {
        return JuggCompileStatusHolder(processHandler, progressIndicator, logger)
    }

    override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener {
        return GradleOutputParser(juggGradleCompileOptions, processHandler, progressIndicator, logger)
    }

    override fun confirmFallbackWhenNoFileChanges(): ConfirmResult {
        isGradleCacheRefreshRequested = false
        if (isRpcMode) {
            return ConfirmResult.NEGATIVE
        }
        // androidTest scenario: skip dialog, proceed without gradle fallback
        if (testEventSinkFactory != null) {
            return ConfirmResult.NEGATIVE
        }
        return CommonConfirmDialog.showAndGetOrCancel(
            title = "Confirm Fallback to Gradle",
            content = "No file changes, do you want to fallback to gradle?",
            okButtonText = "Fallback to Gradle",
            negativeButtonText = "Don't fallback",
            leftButtonText = "Cancel",
            checkBoxText = "clean gradle cache on fallback",
            checkBoxSelectionAction = { isGradleCacheRefreshRequested = it },
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

    override fun confirmEmbeddedToApk(): ConfirmResult {
        if (isRpcMode) {
            return ConfirmResult.POSITIVE
        }
        return CommonConfirmDialog.showAndGetOrCancel(
            "Embedded to APK is Enabled",
            "<html>Embedded to APK is enabled, which will cost more time to deploy.<br>Do you still need it?</html>",
            okButtonText = "Yes, embed to APK",
            negativeButtonText = "No, disable embedded mode",
        )
    }

    override fun confirmTooManyChanges(info: TooManyChangesInfo): TooManyChangesConfirmResult {
        if (isRpcMode) {
            return TooManyChangesConfirmResult.FALLBACK
        }
        return TooManyChangesConfirmDialog.showAndGetResult(info)
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

    override fun ensureRunWindowCreated() {
        if (isRpcMode) {
            return
        }
        SwingUtilities.invokeLater {
            val manager = RunContentManager.getInstance(project)
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            runCatching {
                val method = manager.javaClass
                    .methods
                    .firstOrNull { method ->
                        method.name == "registerToolWindow" &&
                            method.parameterTypes.contentEquals(arrayOf(Executor::class.java))
                    }
                if (method == null) {
                    logger.warn("RunContentManager does not expose registerToolWindow.")
                } else {
                    method.invoke(manager, executor)
                }
            }.onFailure {
                logger.warn("Failed to create Run tool window without activation.", it)
            }
        }
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
            logger.info(message)
            return
        }
        processHandler.notifyTextAvailable("$message\n", ProcessOutputType.STDOUT)
    }

    override fun onEnd(runResult: RunResult) {
        onEndListener?.invoke(runResult)
    }

    override fun cancel() {
        return processHandler.detachProcess()
    }
}
