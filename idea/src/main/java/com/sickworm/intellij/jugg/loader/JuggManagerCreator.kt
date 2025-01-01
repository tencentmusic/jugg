package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.IJuggManager
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.ide.IdeaPlatformApi
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * Create and release [JuggManager].
 * Extract this class isolated to make sure it's running in a separate class loader.
 */
class JuggManagerCreator(
    private val project: Project,
    private val projectDir: File,
    private val creatorName: String,
    ): IJuggManagerCreator {

    private var juggManager: IJuggManager? = null

    override fun create(): IJuggManager {
        if (juggManager != null) {
            throw IllegalStateException("Jugg already init on ${projectDir}, aborted.")
        }

        PlatformApi.impl = IdeaPlatformApi()

        val pathManager = JuggPathManager(projectDir)
        JuggLogger.register(project, pathManager.logDir)

        try {
            val logger = JuggLogger.getInstance(project, "JuggManagerCreator")
            logger.info("Start Init Jugg by $creatorName on ${project.basePath}")
            val juggManager = JuggManager(project, pathManager)
            juggManager.init()
            this.juggManager = juggManager
            return juggManager
        } catch (e: Exception) {
            // oops, release file handler
            JuggLogger.unregister(project)
            throw e
        }
    }

    override fun release() {
        val logger = JuggLogger.getInstance(project, "JuggManagerCreator")
        logger.info("Release Jugg on ${project.basePath}")
        juggManager?.let {
            Disposer.dispose(it)
        }
        JuggLogger.unregister(project)
    }
}


interface IJuggManagerCreator {
    fun create(): IJuggManager
    fun release()
}