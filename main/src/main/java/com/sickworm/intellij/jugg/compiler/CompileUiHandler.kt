package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import java.io.File

/**
 * Handle user interaction events
 */
interface CompileUiHandler {
    val isForceInstall: Boolean
    val isCanceled: Boolean

    fun createCompileStatusHolder(): CompileStatusHolder
    fun createOutputParser(): IGradleCompileClient.TerminalOutputListener

    fun confirmFallbackWhenNoFileChanges(): ConfirmResult
    fun confirmBuildChanges(project: Project, changedBuildFiles: List<Pair<File, File?>>): BuildChangesConfirmResult
    fun confirmDependencyChanges(dependencyChangeManager: IDependencyChangeManager, runResult: DependencyDiffResultSet?): ConfirmResult

    fun updateIndicatorText(text: String)
    fun listenCancelAction(listener: (() -> Unit)?)

    fun cancel()

    companion object {
        val DEFAULT = object : CompileUiHandler {
            override val isForceInstall: Boolean = false
            override val isCanceled: Boolean = false

            override fun createCompileStatusHolder(): CompileStatusHolder = CompileStatusHolder.DEFAULT
            override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

            override fun confirmFallbackWhenNoFileChanges() = ConfirmResult.NEGATIVE
            override fun confirmBuildChanges(project: Project, changedBuildFiles: List<Pair<File, File?>>) = BuildChangesConfirmResult.FALLBACK
            override fun confirmDependencyChanges(dependencyChangeManager: IDependencyChangeManager, runResult: DependencyDiffResultSet?) = ConfirmResult.POSITIVE

            override fun updateIndicatorText(text: String) = Unit
            override fun listenCancelAction(listener: (() -> Unit)?) = Unit

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