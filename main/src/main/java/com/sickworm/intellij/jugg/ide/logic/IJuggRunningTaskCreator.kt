package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions

/**
 * IJuggRunningTaskCreator interface for creating and starting concrete [IJuggRunningTask] instances.
 * Collaboration: Called by configuration runner flows with [JuggGradleCompileOptions] and [CompileUiHandler].
 */
interface IJuggRunningTaskCreator {
    fun createAndRun(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler): IJuggRunningTask
}
