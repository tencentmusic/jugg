package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient

/**
 * Handle user interaction events
 */
interface CompileUiHandler {
    val isForceInstall: Boolean
    val isCanceled: Boolean
    val compileStatusHolder: CompileStatusHolder
    val outputParser: IGradleCompileClient.TerminalOutputListener

    fun updateIndicatorText(text: String)
    fun listenCancelAction(listener: (() -> Unit)?)
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