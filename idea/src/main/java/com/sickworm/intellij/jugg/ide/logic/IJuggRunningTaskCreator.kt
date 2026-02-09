package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions

interface IJuggRunningTaskCreator {
    fun create(options: JuggRunConfigurationOptions, compileUiHandler: CompileUiHandler): JuggRunningTask
}