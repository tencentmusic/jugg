package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.JuggPathManager

class BaseBuildCommandHelper(pathManager: JuggPathManager) {

    private val recordFile = pathManager.baseBuildCmdFile

    val hasBaseBuildCmd: Boolean get() = recordFile.exists()

    fun recordBaseBuildCmd(options: JuggGradleCompileOptions) {
        recordFile.parentFile.mkdirs()
        recordFile.delete()
        recordFile.writeText(options.compileCommand)
    }

    fun getBaseBuildCmd(): String? {
        if (!recordFile.exists()) {
            return null
        }
        return recordFile.readText()
    }
}