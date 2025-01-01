package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.IJuggManager
import com.sickworm.intellij.jugg.loader.JuggLoader
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File

object JuggInitializer {

    private val instanceSet = mutableMapOf<String, JuggLoader>()

    private val logger = Logger.getInstance("JuggInitializer")


    @Synchronized
    fun onSyncEvent(project: Project, syncEvent: SyncEvent) {
        val instance = instanceSet[project.basePath]
        instance?.juggManager?.onSyncEvent(syncEvent)
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

        val instance = JuggLoader(project, File(projectDir))
        instanceSet[projectDir] = instance
        instance.init()
    }

    @Synchronized
    fun release(project: Project) {
        val instance = instanceSet.remove(project.bashPathOrDefault)
        instance?.release()
    }

    fun getManager(project: Project?): IJuggManager? {
        if (project == null) {
            return null
        }
        return instanceSet[project.bashPathOrDefault]?.juggManager
    }
}

val Project.bashPathOrDefault get() = basePath ?: "null"