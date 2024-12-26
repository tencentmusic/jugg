package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.loader.IJuggManager
import com.sickworm.intellij.jugg.loader.JuggLoader
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

object JuggInitializer {

    private val instanceSet = mutableMapOf<String, IJuggManager>()

    private val logger = Logger.getInstance("JuggInitializer")

    private fun tryGetProjectLogger(project: Project) = try {
        JuggLogger.getInstance(project, "JuggInitializer")
    } catch (e: Exception) {
        null
    }


    @Synchronized
    fun onSyncEvent(project: Project, syncEvent: SyncEvent) {
        val juggManager = instanceSet[project.basePath]
        juggManager?.onSyncEvent(syncEvent)
    }

    @Synchronized
    fun init(project: Project) {
        val projectDir: String? = project.basePath
        if (projectDir == null || !File(projectDir).exists()) {
            logger.error("Can not get Jugg project dir, exit")
            return
        }

        if (instanceSet.containsKey(projectDir)) {
            logger.debug("Jugg already init on ${projectDir}, exit init")
            return
        }

        val juggManager = JuggLoader.loadManager(project, File(projectDir))
        instanceSet[projectDir] = juggManager
    }

    @Synchronized
    fun release(project: Project) {
        val juggManager = instanceSet.remove(project.bashPathOrDefault)
        tryGetProjectLogger(project)?.info("Release Jugg on ${project.basePath}")

        juggManager ?: return
        Disposer.dispose(juggManager)
        JuggLogger.unregister(project)
    }

    fun getManager(project: Project?): IJuggManager? {
        if (project == null) {
            return null
        }
        return instanceSet[project.bashPathOrDefault]
    }
}

enum class SyncEvent {
    STARTED,
    SKIPPED,
    SUCCEEDED,
    FAILED
}

val Project.bashPathOrDefault get() = basePath ?: "null"