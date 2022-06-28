package com.sickworm.intellij.jugg.project

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File

/**
 * Declaration of path usage for Jugg.
 */
class JuggPathManager(
    val project: Project,
    val projectDir: File,
    val juggRootDir: File = File("$projectDir/build/jugg")
) {
    val compileRootDir = File(juggRootDir, "build")
    val historyDir = File(juggRootDir, "database")
    val logDir = File(juggRootDir, "log")

    init {
        JuggLogger.register(project, logDir)
    }
}