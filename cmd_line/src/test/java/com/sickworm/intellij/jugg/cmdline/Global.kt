package com.sickworm.intellij.jugg.cmdline

import java.io.File

object Global {

    private val rootDir = File("../").normalize().absoluteFile

    val projectRootDir: File = File(rootDir, "android_demo_project").absoluteFile

    val buildOutputDir = File(rootDir, "cmd_line/src/test/build")
}