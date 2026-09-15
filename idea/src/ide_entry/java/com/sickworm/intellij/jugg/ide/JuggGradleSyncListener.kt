package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.loader.JuggInitializer

/**
 * Forwards Gradle sync events without depending on the root-aware listener API.
 */
class JuggGradleSyncListener : GradleSyncListener {

    private val ideaLogger = Logger.getInstance("JuggGradleSyncListener")

    override fun syncStarted(project: Project) {
        ideaLogger.info("syncStarted $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.STARTED)
    }

    override fun syncSucceeded(project: Project) {
        ideaLogger.info("syncSucceeded $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.SUCCEEDED)
    }

    override fun syncSkipped(project: Project) {
        ideaLogger.info("syncSkipped $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.SKIPPED)
    }

    override fun syncFailed(project: Project, errorMessage: String) {
        ideaLogger.info("syncFailed $project $errorMessage")
        JuggInitializer.onSyncEvent(project, SyncEvent.FAILED)
    }
}
