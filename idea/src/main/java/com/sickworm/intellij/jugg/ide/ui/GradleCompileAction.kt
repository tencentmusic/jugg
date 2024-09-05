package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sickworm.intellij.jugg.ide.JuggInitializer

/**
 * button to gradle compile
 */
class GradleCompileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val juggManager = JuggInitializer.getManager(project) ?: return
        juggManager.gradleCompile()
    }
}