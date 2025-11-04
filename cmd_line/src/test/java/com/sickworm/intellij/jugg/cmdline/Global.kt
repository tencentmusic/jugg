package com.sickworm.intellij.jugg.cmdline

import java.io.File

object Global {

    private val rootDir = File("../").absoluteFile

    val projectRootDir: File = File(rootDir, "idea/src/test/assets/android/MyApplicationIntellij").absoluteFile
}