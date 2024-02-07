package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.gradle.project.sync.GradleSyncListenerWithRoot
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.SystemIndependent


class JuggGradleSyncWithRootListener : GradleSyncListenerWithRoot {

    companion object {
        @Volatile
        var isEnabled = true
    }

    private val ideaLogger = Logger.getInstance("JuggGradleSyncWithRootListener")

    override fun syncStarted(project: Project, rootProjectPath: @SystemIndependent String) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncStarted $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.STARTED)
    }

    override fun syncSucceeded(project: Project, rootProjectPath: @SystemIndependent String) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncSucceeded $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.SUCCEEDED)
    }

    override fun syncSkipped(project: Project) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncSkipped $project")
        JuggInitializer.onSyncEvent(project, SyncEvent.SKIPPED)
    }

    override fun syncFailed(project: Project, errorMessage: String, rootProjectPath: @SystemIndependent String) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncFailed $project $errorMessage")
        JuggInitializer.onSyncEvent(project, SyncEvent.FAILED)
    }
}