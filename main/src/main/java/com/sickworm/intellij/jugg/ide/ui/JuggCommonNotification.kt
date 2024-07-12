package com.sickworm.intellij.jugg.ide.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.server.protocols.NotificationData

/**
 * Show notification for Jugg.
 */
class JuggCommonNotification(private val project: Project) {

    fun showUpgrade(downloadUrl: String) {
        show(NotificationData("Jugg is ready to upgrade", "", "Download", downloadUrl))
    }

    fun show(data: NotificationData) {
        try {
            doShow(data)
        } catch (e: Throwable) {
            // ignore, catch for test
        }
    }

    private fun doShow(data: NotificationData) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Jugg Notification Group")
            .createNotification(
                data.title, data.content,
                NotificationType.INFORMATION
            )
        notification.addAction(object : AnAction(data.buttonText) {
            override fun actionPerformed(e: AnActionEvent) {
                // open download url
                BrowserUtil.browse(data.jumpUrl)
            }
        })
        notification.notify(project)
    }
}
