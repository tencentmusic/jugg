package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project


/**
 * won't call back after Android Studio Giraffe
 */
class JuggGradleSyncListener : GradleSyncListener {

    private val ideaLogger = Logger.getInstance("JuggGradleSyncListener")

    override fun syncStarted(project: Project) {
        disableRootListener()
        ideaLogger.info("syncStarted $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.STARTED)
    }

    override fun syncSucceeded(project: Project) {
        ideaLogger.info("syncSucceeded $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.SUCCEEDED)
    }

    override fun syncSkipped(project: Project) {
        disableRootListener()
        ideaLogger.info("syncSkipped $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.SKIPPED)
    }

    override fun syncFailed(project: Project, errorMessage: String) {
        disableRootListener()
        ideaLogger.info("syncFailed $project $errorMessage")
        JuggInitializer.onSyncEvent(project, SyncEvent.FAILED)
    }

    private fun disableRootListener() {
        try {
            JuggGradleSyncWithRootListener.isEnabled = false
        } catch (e: Throwable) {
            // Cannot load class com.android.tools.idea.gradle.project.sync.GradleSyncListenerWithRoot
            // ok with that
        }
    }
}