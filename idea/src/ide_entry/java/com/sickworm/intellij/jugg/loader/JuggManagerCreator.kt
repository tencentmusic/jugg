package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.ide.IJuggManagerCaller
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
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

    private var juggManager: IJuggManagerCaller? = null

    override fun create(): IJuggManagerCaller {
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

    override fun printCreateError(e: Throwable) {
        val logger = JuggLogger.getInstance(project, "JuggLoader")
        logger.warn("Jugg loading error", e)
        logger.warn("Jugg loading error, use embedded jars.")
        if (isTestEnv) {
            JuggLogger.unregister(project)
            throw e
        }
    }

    private val isTestEnv: Boolean
        get() = PathManager.getSystemPath().replace("\\", "/").contains("idea/build/idea-sandbox/system")

}


interface IJuggManagerCreator {
    fun create(): IJuggManagerCaller
    fun release()
    fun printCreateError(e: Throwable)
}
