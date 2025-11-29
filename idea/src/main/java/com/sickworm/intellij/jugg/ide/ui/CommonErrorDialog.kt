package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object CommonErrorDialog {
    fun show(project: Project, title: String, message: String) {
        ApplicationManager.getApplication().invokeLater({
            Messages.showErrorDialog(project, message, title)
        }, ModalityState.defaultModalityState())
    }
}