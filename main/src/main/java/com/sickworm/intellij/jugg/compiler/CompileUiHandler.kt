package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import java.io.File

/**
 * Handle user interaction events
 */
interface CompileUiHandler {
    var isForceGradleCompile: Boolean
    val isSkipDeploy: Boolean
    /** When true, always restart app after deployment regardless of deploy type (HOT_FIX behavior). */
    val isAlwaysRestartApp: Boolean
    val isCanceled: Boolean
    var processHandler: IProcessHandler // injected
    var progressIndicator: ProgressIndicator // injected
    var testEventSinkFactory: ((String, Boolean) -> ((InstrumentationEvent) -> Unit)?)?

    fun createCompileStatusHolder(): CompileStatusHolder
    fun createOutputParser(): IGradleCompileClient.TerminalOutputListener

    fun confirmFallbackWhenNoFileChanges(): ConfirmResult
    fun confirmBuildChanges(project: Project, changedBuildFiles: List<Pair<File, File?>>): BuildChangesConfirmResult
    fun confirmDependencyChanges(dependencyChangeManager: IDependencyChangeManager, runResult: DependencyDiffResultSet?): ConfirmResult

    fun updateIndicatorText(text: String)
    fun listenCancelAction(listener: (() -> Unit)?)
    fun notifyByBalloon(text: String)
    fun showRunWindow()

    fun shouldAutoConfirmDeployPrompt(message: String): Boolean = false
    fun onDeployUiMessage(message: String) = Unit

    fun onEnd(runResult: RunResult) = Unit

    fun cancel()

    companion object {
        val DEFAULT = object : CompileUiHandler {
            override var isForceGradleCompile: Boolean = false
            override val isSkipDeploy: Boolean = false
            override val isCanceled: Boolean = false
            override val isAlwaysRestartApp: Boolean = false
            override var processHandler: IProcessHandler = IProcessHandler.DEFAULT
            override var progressIndicator: ProgressIndicator = DumbProgressIndicator()
            override var testEventSinkFactory: ((String, Boolean) -> ((InstrumentationEvent) -> Unit)?)? = null

            override fun createCompileStatusHolder(): CompileStatusHolder = CompileStatusHolder.DEFAULT
            override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

            override fun confirmFallbackWhenNoFileChanges() = ConfirmResult.NEGATIVE
            override fun confirmBuildChanges(project: Project, changedBuildFiles: List<Pair<File, File?>>) = BuildChangesConfirmResult.FALLBACK
            override fun confirmDependencyChanges(dependencyChangeManager: IDependencyChangeManager, runResult: DependencyDiffResultSet?) = ConfirmResult.POSITIVE

            override fun updateIndicatorText(text: String) = Unit
            override fun listenCancelAction(listener: (() -> Unit)?) = Unit
            override fun notifyByBalloon(text: String) = Unit
            override fun showRunWindow() = Unit

            override fun cancel() = Unit
        }
    }
}


/**
 * Get and notify the compile status
 */
interface CompileStatusHolder {

    val isShouldCancel: Boolean

    fun setCompileFiles(files: List<CompileFile>)

    fun onFilesCompiled(files: List<CompileFile>)

    fun cancel()

    companion object {
        val DEFAULT = object : CompileStatusHolder {
            override var isShouldCancel: Boolean = false
            override fun setCompileFiles(files: List<CompileFile>) = Unit
            override fun onFilesCompiled(files: List<CompileFile>) = Unit
            override fun cancel() {
                isShouldCancel = true
            }
        }
    }
}
