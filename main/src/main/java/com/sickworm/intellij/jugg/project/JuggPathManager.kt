package com.sickworm.intellij.jugg.project

import com.intellij.openapi.project.Project
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
    val databaseDir = File(juggRootDir, "database")
    val logDir = File(juggRootDir, "log")
    val tmpDir = File(juggRootDir, "tmp")
    val configDir = File(juggRootDir, "config")

    val projectInfosDir = File(databaseDir, "project_infos.db")

    val localClasspathStoragePathManager = LocalClasspathStoragePathManager(File(juggRootDir, "classpath"))
}

class LocalClasspathStoragePathManager(
    val rootDir: File,
) {
    val classpathDir: File = File(rootDir, "root")
    val apkDir: File = File(rootDir, "apk")

    override fun toString(): String {
        return "LocalClasspathStoragePathManager(rootDir=$rootDir)"
    }
}