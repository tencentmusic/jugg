package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.CustomConfigManager
import com.sickworm.intellij.jugg.server.protocols.VersionData

/**
 * Handle check update result from Jugg server.
 */
class CheckUpdateHandler(
    private val project: Project,
    private val currentVersion: String,
    private val customConfigManager: CustomConfigManager,
    private val logger: Logger,
    private val refreshSettings: () -> Unit,
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

        if (versionData.customConfigJson != null) {
            versionData.customConfigJson?.let { config ->
                customConfigManager.updateDefaultConfig(config)
                try {
                    if (ProjectDefaultSettingsApplier(project, logger.getInstance("ProjectDefaultSettingsApplier")).apply(config)) {
                        try {
                            refreshSettings()
                        } catch (e: Exception) {
                            logger.warn("Refresh Jugg settings failed", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Apply project default settings failed", e)
                }
            }
        }
    }
}
