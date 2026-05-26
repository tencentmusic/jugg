package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

object TestGlobal {

    val logger: Logger = Logger.getInstance("TestGlobal")

    private val rootDir = File("../").absoluteFile

    val projectRootDir: File = File(rootDir, "idea/src/test/assets/android/MyApplicationIntellij").absoluteFile

    val buildOutputDir = File(rootDir, "cmd_line/src/test/build")

    val androidHome = File(System.getenv("ANDROID_HOME")?: throw IllegalStateException("please specific ANDROID_HOME in env"))

    val javaHome = File(System.getProperty("java.home")?: throw IllegalStateException("please specific java home in env"))

    init {
        PlatformApi.impl = TestPlatformApi()
    }
}