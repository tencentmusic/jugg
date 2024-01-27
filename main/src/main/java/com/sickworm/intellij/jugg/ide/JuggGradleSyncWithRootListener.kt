package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.gradle.project.sync.GradleSyncListenerWithRoot
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.gradle.model.Validate
import org.jetbrains.annotations.SystemIndependent


class JuggGradleSyncWithRootListener : GradleSyncListenerWithRoot {

    companion object {
        @Volatile
        var isEnabled = true
    }

    private val ideaLogger = Logger.getInstance("JuggGradleSyncWithRootListener")

    private fun tryGetProjectLogger(project: Project) = try {
        JuggLogger.getInstance(project, "JuggGradleSyncWithRootListener")
    } catch (e: Exception) {
        null
    }

    override fun syncStarted(project: Project, rootProjectPath: @SystemIndependent String) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncStarted $project")
        tryGetProjectLogger(project)?.info("syncStarted")
    }

    override fun syncSucceeded(project: Project, rootProjectPath: @SystemIndependent String) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncSucceeded $project")
        JuggInitializer.initOrRefresh(project)
        tryGetProjectLogger(project)?.info("syncSucceeded")
    }

    override fun syncSkipped(project: Project) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncSkipped $project")
        tryGetProjectLogger(project)?.info("syncSkipped")
    }

    override fun syncFailed(project: Project, errorMessage: String, rootProjectPath: @SystemIndependent String) {
        if (!isEnabled) {
            return
        }
        ideaLogger.info("syncFailed $project $errorMessage")
        JuggInitializer.initOrRefresh(project)
        tryGetProjectLogger(project)?.info("syncFailed $errorMessage")
    }
}