package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Opens the project-level Jugg control panel. */
class OpenJuggControlPanelAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(JuggControlPanel::open)
    }
}
