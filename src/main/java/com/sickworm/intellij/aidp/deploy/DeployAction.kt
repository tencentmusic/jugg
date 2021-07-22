package com.sickworm.intellij.aidp.deploy

import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.util.CommonAndroidUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.sickworm.intellij.aidp.AidpManager

class DeployAction: AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        // Using the event, create and show a dialog
        val currentProject = event.project!!
        // If an element is selected in the editor, add info about it.
        Messages.showMessageDialog(currentProject, currentText, "AIDP", Messages.getInformationIcon())
    }

    private var currentText: String = "unknown state"
    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project
        if (project == null || !CommonAndroidUtil.getInstance().isAndroidProject(project)) {
            return
        }

        val disableMessage = BaseAction.getDisableMessage(project)
        currentText = disableMessage?.description ?: "ready to run"

        val aidpManager = AidpManager.getInstance(project)
        aidpManager?.updateStatus(currentText)
    }
}