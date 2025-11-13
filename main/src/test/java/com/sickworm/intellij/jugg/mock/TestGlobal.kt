package com.sickworm.intellij.jugg.mock

import java.io.File

object TestGlobal {

    private val rootDir = File("../").absoluteFile

    val projectRootDir: File = File(rootDir, "idea/src/test/assets/android/MyApplicationIntellij").absoluteFile

    val buildOutputDir = File(rootDir, "cmd_line/src/test/build")
}