package com.sickworm.intellij.jugg.project

import java.io.File

/**
 * Declaration of path usage for Jugg.
 */
class JuggPathManager(val projectDir: File) {

    val juggRootDir = File("$projectDir/build/jugg")

    val compileRootDir = File(juggRootDir, "build")
    val stagingDir = File(compileRootDir, "staging")
    val databaseDir = File(juggRootDir, "database")
    val logDir = File(juggRootDir, "log")
    val tmpDir = File(juggRootDir, "tmp")
    val configDir = File(juggRootDir, "config")

    val projectInfosDir = File(databaseDir, "project_infos.db")
    val projectInfoJsonFile = File(projectInfosDir, "project_infos.json")
    val gradleProjectInfoFile = File(projectInfosDir, "gradle_project_infos.json")
    val markProjectInfoNeedUpdateFlagFile = File(projectInfosDir, "is_dirty")

    val localClasspathStoragePathManager = LocalClasspathStoragePathManager(File(juggRootDir, "classpath"))

    val initGradleFilePath = File(configDir, "readProjectInfo.gradle.kts")
    val initGradleFileRelativePath: String = initGradleFilePath.relativeTo(projectDir).path

    companion object {
        const val RSYNC_PUSH_CONFIG_DIR_ARGUMENTS = "--include='/build' --include='/build/jugg' --include='/build/jugg/config' --include='/build/jugg/config/**' --exclude='/build/**'"
        const val RSYNC_FETCH_CONFIG_DIR_ARGUMENTS = "--include='build/jugg/database/project_infos.db/**'"
    }
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
