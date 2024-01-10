package com.sickworm.intellij.jugg.server

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.JuggCommonNotification
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.server.protocols.VersionData

/**
 * Handle check update result from Jugg server.
 */
class CheckUpdateHandler(
    private val project: Project,
    private val currentVersion: String,
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
        }
        if (versionData.templateList.isNotEmpty()) {
            JuggSettings.compileTemplateList = versionData.templateList
        } else if (versionData.popupText != null && versionData.popupUrl != null) {
            JuggCommonNotification(project).show(versionData.popupText, versionData.popupUrl)
        }
    }
}