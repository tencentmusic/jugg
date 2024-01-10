package com.sickworm.intellij.jugg.ide

import com.intellij.ide.BrowserUtil
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Show notification for Jugg.
 */
class JuggCommonNotification(private val project: Project) {

    fun showUpgrade(downloadUrl: String) {
        show("Jugg is ready to upgrade", downloadUrl)
    }

    fun show(text: String, downloadUrl: String) {
        try {
            doShow(text, downloadUrl)
        } catch (e: Throwable) {
            // ignore, catch for test
        }
    }

    private fun doShow(text: String, downloadUrl: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Jugg Notification Group")
            .createNotification(
                text, "",
                NotificationType.INFORMATION
            )
        notification.addAction(object : AnAction("Download") {
            override fun actionPerformed(e: AnActionEvent) {
                // open download url
                BrowserUtil.browse(downloadUrl)
            }
        })
        notification.notify(project)
    }
}
