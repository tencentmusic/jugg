package com.sickworm.intellij.jugg.ui

import com.sickworm.intellij.jugg.ide.JuggRunSettingsComponent
import javax.swing.JFrame

object JuggRunConfigurationSettingsPreviewer {
    @JvmStatic
    fun main(args: Array<String>) {
        val frame = JFrame("JuggRunSettingsComponent")
        val juggRunSettingsComponent = JuggRunSettingsComponent()
        frame.contentPane = juggRunSettingsComponent
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.pack()
        frame.isVisible = true
    }
}