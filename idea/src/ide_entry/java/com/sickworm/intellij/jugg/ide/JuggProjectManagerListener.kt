package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.logger.JuggLogger

class JuggProjectManagerListener : ProjectManagerListener {

    private val ideaLogger = Logger.getInstance("JuggProjectManagerListener")

    override fun projectOpened(project: Project) {
        JuggInitializer.init(project)
        subscribeGradleSync(project)
    }

    override fun projectClosed(project: Project) {
        // no callback in runIde
    }

    override fun projectClosing(project: Project) {
        JuggInitializer.release(project)
    }

    private fun subscribeGradleSync(project: Project) {
        try {
            // GradleSyncState changed from class to interface, so invoke its stable static bridge reflectively.
            val syncStateClass = Class.forName(GRADLE_SYNC_STATE_CLASS)
            syncStateClass.getMethod(
                "subscribe",
                Project::class.java,
                GradleSyncListener::class.java,
                Disposable::class.java,
            ).invoke(null, project, JuggGradleSyncListener(), project)
        } catch (e: Throwable) {
            val logger = JuggLogger.getInstanceSafe(project, "JuggProjectManagerListener") ?: ideaLogger
            logger.warn("Failed to subscribe Gradle sync listener", e)
        }
    }

    companion object {
        private const val GRADLE_SYNC_STATE_CLASS =
            "com.android.tools.idea.gradle.project.sync.GradleSyncState"
    }
}
