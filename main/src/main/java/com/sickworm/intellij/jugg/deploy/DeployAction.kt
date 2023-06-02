@file:Suppress("UnstableApiUsage")

package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.util.CommonAndroidUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.sickworm.intellij.jugg.JuggManager

private const val NAME = "Jugg Deploy"

private const val DESC = "Attempt to apply resource and code changes by Jugg."

/**
 * Usage:
 * 1. Register Jugg Deploy.
 * 2. listen and update deploy state.
 */
class DeployAction(
    private val juggManager: JuggManager,
): AnAction(
    NAME, DESC, AllIcons.Actions.Execute
) {

    override fun actionPerformed(event: AnActionEvent) {
        // Using the event, create and show a dialog
        val currentProject = event.project?: return
        // If an element is selected in the editor, add info about it.
        Messages.showMessageDialog(currentProject, currentText, "Jugg", Messages.getInformationIcon())
    }

    private var currentText: String = "unknown state"

    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project
        if (project == null || !CommonAndroidUtil.getInstance().isAndroidProject(project)) {
            return
        }

        val deployState = juggManager.updateDeployState()
        currentText = deployState.msg
    }
}

