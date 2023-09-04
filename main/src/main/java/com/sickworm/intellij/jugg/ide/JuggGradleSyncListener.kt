package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.JuggLogger


/**
 * won't call back after Android Studio Giraffe
 */
class JuggGradleSyncListener : GradleSyncListener {

    private val ideaLogger = Logger.getInstance("JuggGradleSyncListener")

    private fun tryGetProjectLogger(project: Project) = try {
        JuggLogger.getInstance(project, "JuggGradleSyncListener")
    } catch (e: Exception) {
        null
    }

    override fun syncStarted(project: Project) {
        JuggGradleSyncWithRootListener.isEnabled = false
        ideaLogger.info("syncStarted $project")
        tryGetProjectLogger(project)?.info("syncStarted")
    }

    override fun syncSucceeded(project: Project) {
        JuggGradleSyncWithRootListener.isEnabled = false
        ideaLogger.info("syncSucceeded $project")
        JuggInitializer.initOrRefresh(project)
        tryGetProjectLogger(project)?.info("syncSucceeded")
    }

    override fun syncSkipped(project: Project) {
        JuggGradleSyncWithRootListener.isEnabled = false
        ideaLogger.info("syncSkipped $project")
        JuggInitializer.initOrRefresh(project, isNeedReloadProjectInfo = false)
        tryGetProjectLogger(project)?.info("syncSkipped")
    }

    override fun syncFailed(project: Project, errorMessage: String) {
        JuggGradleSyncWithRootListener.isEnabled = false
        ideaLogger.info("syncFailed $project $errorMessage")
        JuggInitializer.initOrRefresh(project)
        tryGetProjectLogger(project)?.info("syncFailed $errorMessage")
    }
}