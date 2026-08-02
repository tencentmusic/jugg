package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sickworm.intellij.jugg.loader.JuggInitializer

/**
 * button to restart app
 */
class RestartAppAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hasRunnableJuggConfiguration(e.project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val juggManager = JuggInitializer.getManager(project) ?: return
        juggManager.restartApp()
    }
}
