package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler

/**
 * Show compile progress to indicator
 */
class JuggCompileStatusHolder(
    private val processHandler: IProcessHandler,
    private val indicator: ProgressIndicator,
    private val logger: Logger,
) : CompileStatusHolder {

    private var setTimes = 0
    private var allCompileFiles: MutableSet<CompileFile> = mutableSetOf()
    private val compiledFiles: MutableSet<CompileFile> = mutableSetOf()

    override val isShouldCancel: Boolean
        get() = processHandler.isCanceled

    override fun setCompileFiles(files: List<CompileFile>) {
        setTimes++
        allCompileFiles = files.toMutableSet()
        compiledFiles.clear()
        log()
    }

    override fun onFilesCompiled(files: List<CompileFile>) {
        compiledFiles.addAll(files.filter { allCompileFiles.contains(it) })
        log()
    }

    private fun log() {
        val total = allCompileFiles.size
        val compiling = (compiledFiles.size + 1).coerceAtMost(total)
        val text = if (setTimes <= 1) {
            "Compiling files (${compiling}/$total)..."
        } else {
            "Compiling effected files ($compiling/$total)..."
        }
        logger.debug(text)
        indicator.text = text
    }

    override fun cancel() {
        processHandler.detachProcess()
    }

}