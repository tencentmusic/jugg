package com.sickworm.intellij.jugg.ide.ui

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.loader.JuggInitializer

/**
 * button to gradle compile
 */
class GradleCompileAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hasRunnableJuggConfiguration(e.project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val juggManager = JuggInitializer.getManager(project) ?: return
        juggManager.gradleCompile()
    }
}

internal fun hasRunnableJuggConfiguration(project: Project?): Boolean {
    project ?: return false
    return RunManager.getInstance(project)
        .getConfigurationSettingsList(JuggConfigurationType::class.java)
        // Legacy versions could create jugg:default after a timeout; it does not prove Android support.
        .any { !SuggestRunConfiguration.isDefaultRunConfigName(it.name) }
}
