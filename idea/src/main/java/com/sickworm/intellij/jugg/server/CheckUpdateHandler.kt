package com.sickworm.intellij.jugg.server

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.ui.JuggCommonNotification
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.project.CustomConfigManager
import com.sickworm.intellij.jugg.server.protocols.VersionData

/**
 * Handle check update result from Jugg server.
 */
class CheckUpdateHandler(
    private val project: Project,
    private val currentVersion: String,
    private val customConfigManager: CustomConfigManager,
    private val logger: Logger
) {

    fun handle(versionData: VersionData) {
        logger.debug("Check update result: $versionData")
        if (versionData.isNeedUpgrade) {
            val prefix = if (versionData.downloadUrl.contains("?")) {
                "&"
            } else {
                "?"
            }
            val downloadUrl = versionData.downloadUrl + prefix + "version=${currentVersion}"
            JuggCommonNotification(project).showUpgrade(downloadUrl)
        } else if (versionData.notification != null) {
            versionData.notification?.let {
                JuggCommonNotification(project).show(it)
            }
        }

        if (versionData.templateList.isNotEmpty()) {
            JuggSettings.compileTemplateList = versionData.templateList
        }

        if (versionData.customConfigJson != null) {
            versionData.customConfigJson?.let {
                customConfigManager.updateDefaultConfig(it)
            }
        }
    }
}