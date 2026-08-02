package com.sickworm.intellij.jugg.project

import java.io.File

/**
 * Centralizes Jugg-owned global files under the user's home directory.
 */
object JuggGlobalPathManager {

    val rootDir = File(System.getProperty("user.home"), ".jugg")

    val hotUpdateDir: File
        get() = hotUpdateDir(rootDir)

    val deployCacheDbFile: File
        get() = deployCacheDbFile(rootDir)

    val actionDbFile: File
        get() = File(rootDir, "action.db")

    fun resourceFile(resourcePath: String, rootDir: File = this.rootDir): File {
        val relativePath = resourcePath.trimStart('/', File.separatorChar)
        return File(File(rootDir, "resources"), relativePath)
    }

    fun hotUpdateDir(rootDir: File = this.rootDir): File = File(rootDir, "hot_update")

    fun deployCacheDbFile(rootDir: File = this.rootDir): File = File(rootDir, "deploy_cache/.deploy_cache.db")
}
