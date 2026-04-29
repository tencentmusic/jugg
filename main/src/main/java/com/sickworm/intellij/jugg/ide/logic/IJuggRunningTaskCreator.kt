package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions

/**
 * IJuggRunningTaskCreator interface for creating and starting concrete [IJuggRunningTask] instances.
 * Collaboration: Called by configuration runner flows with [JuggGradleCompileOptions] and [CompileUiHandler].
 */
interface IJuggRunningTaskCreator {
    fun createAndRun(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler): IJuggRunningTask

    fun createAndRun(
        options: JuggGradleCompileOptions,
        compileUiHandler: CompileUiHandler,
        androidTestRunSpec: AndroidTestRunSpec?,
    ): IJuggRunningTask = createAndRun(options, compileUiHandler)
}
