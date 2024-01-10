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
    val juggRootDir: File,
) {
    constructor(project2: Project, projectDir: File) : this(project2, projectDir, File("$projectDir/build/jugg"))

    val compileRootDir = File(juggRootDir, "build")
    val historyDir = File(juggRootDir, "database")
    val logDir = File(juggRootDir, "log")
    val tmpDir = File(juggRootDir, "tmp").also { it.mkdirs() }
    val configDir = File(juggRootDir, "config")
}