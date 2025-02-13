package com.sickworm.intellij.jugg.ide.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
        val notificationGroup = getGroup()
        val notification = notificationGroup.createNotification(
            data.title, data.content, NotificationType.INFORMATION)
        if (!data.buttonText.isNullOrEmpty()) {
            notification.addAction(object : AnAction(data.buttonText) {
                override fun actionPerformed(e: AnActionEvent) {
                    val jumpUrl = data.buttonText
                    if (!jumpUrl.isNullOrEmpty()) {
                        BrowserUtil.browse(jumpUrl)
                    }
                }
            })
        }
        notification.notify(project)
    }

    private fun getGroup(isSticky: Boolean = true): NotificationGroup {
        if (isSticky) {
            if (NotificationGroup.isGroupRegistered("Jugg Important Notification")) {
                return NotificationGroupManager.getInstance().getNotificationGroup("Jugg Important Notification")
            }
        } else {
            if (NotificationGroup.isGroupRegistered("Jugg Notification")) {
                return NotificationGroupManager.getInstance().getNotificationGroup("Jugg Notification")
            }
        }
        // hot update compat
        return NotificationGroupManager.getInstance().getNotificationGroup("Jugg Notification Group")
    }
}
