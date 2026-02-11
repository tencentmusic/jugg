package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions

interface IJuggRunningTaskCreator {
    fun createAndRun(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler): IJuggRunningTask
}