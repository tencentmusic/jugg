package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.JuggReporter
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException

object JuggInitializer {

    private val instanceSet = mutableMapOf<Project, JuggManager>()

    private val logger = Logger.getInstance("JuggInitializer")

    fun init(project: Project) {

        val projectDir: String? = project.basePath
        if (projectDir == null || !File(projectDir).exists()) {
            logger.error("Can not get Jugg project dir, exit")
            return
        }

        val pathManager = JuggPathManager(project, File(projectDir))
        JuggLogger.register(project, pathManager.logDir)

        val juggManager = JuggManager(project, pathManager)
        juggManager.init()
        instanceSet[project] = juggManager
    }

    fun reset(project: Project) {
        val oldJuggManager = instanceSet[project]?: return
        val pathManager = oldJuggManager.pathManager
        try {
            FileUtils.deleteDirectory(pathManager.juggRootDir)
        } catch (e: IOException) {
            logger.error("Delete root directory failed", e)
        }
        Disposer.dispose(oldJuggManager)

        JuggLogger.register(project, pathManager.logDir)
        instanceSet[project] = JuggManager(project, pathManager)
        instanceSet[project]?.init()
    }

    fun release(project: Project) {
        val juggManager = instanceSet[project] ?: return
        Disposer.dispose(juggManager)
        instanceSet.remove(project)
    }

    fun getManager(project: Project): JuggManager? {
        return instanceSet[project]
    }
}