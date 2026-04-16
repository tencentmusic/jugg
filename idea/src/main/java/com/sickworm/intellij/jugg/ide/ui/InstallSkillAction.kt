package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sickworm.intellij.jugg.loader.JuggInitializer

/**
 * Action equivalent to clicking "Install Jugg Skills" in the More Options menu.
 */
class InstallSkillAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val juggManager = JuggInitializer.getManager(project) ?: return
        juggManager.installSkills()
    }
}
