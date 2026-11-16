package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager

/** Runs the expiry cleanup for Jugg-owned temporary and versioned artifacts. */
class JuggArtifactCleanupManager(
    private val pathManager: JuggPathManager,
    private val logger: Logger,
) {

    fun cleanup() {
        ExpiredArtifactCleaner.cleanupExpiredFiles(
            pathManager.mcpFetchDir,
            logger,
            retentionDays = 30,
        )
        ExpiredArtifactCleaner.cleanupExpiredFiles(
            pathManager.diagnosticsDir,
            logger,
            retentionDays = 7,
        )
        ExpiredArtifactCleaner.cleanupExpiredFiles(
            pathManager.investigationDir,
            logger,
            retentionDays = 30,
        )
        ExpiredArtifactCleaner.cleanupExpiredFiles(
            JuggGlobalPathManager.resourceFile("deploy"),
            logger,
            retentionDays = 30,
        )
    }
}
