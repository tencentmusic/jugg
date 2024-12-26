package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.ide.IdeaPlatformApi
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * Create [JuggManager].
 * Extract this class isolated to make sure it's running in a separate class loader.
 */
class JuggManagerCreator(private val project: Project, private val projectDir: File, private val creatorName: String) {

    fun create(): IJuggManager {
        PlatformApi.impl = IdeaPlatformApi()

        val pathManager = JuggPathManager(projectDir)
        JuggLogger.register(project, pathManager.logDir)

        try {
            val logger = JuggLogger.getInstance(project, "JuggManagerCreator")
            logger.info("Start Init Jugg by $creatorName on ${project.basePath}")
            val juggManager = JuggManager(project, pathManager)
            juggManager.init()

            return juggManager
        } catch (e: Exception) {
            // oops, release file handler
            JuggLogger.unregister(project)
            throw e
        }
    }
}