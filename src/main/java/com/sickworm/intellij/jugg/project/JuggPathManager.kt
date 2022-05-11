package com.sickworm.intellij.jugg.project

import java.io.File

/**
 * Declaration of path usage for Jugg.
 */
class JuggPathManager(
    val projectDir: File,
    val juggRootDir: File = File("$projectDir/build/jugg")
) {
    val compileRootDir = File(juggRootDir, "build")
    val historyDir = File(juggRootDir, "database")
}