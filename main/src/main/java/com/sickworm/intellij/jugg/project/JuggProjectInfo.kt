package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.ModuleInfo

data class JuggProjectInfo(
    val modules: Map<String, ModuleInfo>,
)