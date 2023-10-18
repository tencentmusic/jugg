package com.sickworm.intellij.jugg.ide

import com.intellij.ide.BrowserUtil
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class JuggUpgradeNotification(private val project: Project) {

    fun show(downloadUrl: String) {
        @Suppress("MissingRecentApi")
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Jugg Notification Group")
            .createNotification(
                "Jugg is ready to upgrade", "",
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
