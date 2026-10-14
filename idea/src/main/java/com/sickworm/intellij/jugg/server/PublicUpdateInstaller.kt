package com.sickworm.intellij.jugg.server

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.JuggLogger

/**
 * Handles directing user to appropriate platform update destinations for public channels.
 */
class PublicUpdateInstaller {
    private val logger = JuggLogger.getGlobalLogger("PublicUpdateInstaller")

    /**
     * Opens the update flow:
     * - For Marketplace: opens IDE native "Plugins" settings panel via [ShowSettingsUtil].
     * - For GitHub: opens web browser via [BrowserUtil.browse].
     */
    fun openUpdate(project: Project, publicUpdateInfo: PublicUpdateInfo) {
        logger.debug("Opening update for ${publicUpdateInfo.channel.displayName} (${publicUpdateInfo.targetVersion})")
        when (publicUpdateInfo.channel) {
            UpdateChannel.MARKETPLACE -> {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins")
            }
            UpdateChannel.GITHUB -> {
                val url = publicUpdateInfo.webUrl ?: "https://plugins.jetbrains.com/plugin/34099-jugg"
                BrowserUtil.browse(url)
            }
        }
    }
}
