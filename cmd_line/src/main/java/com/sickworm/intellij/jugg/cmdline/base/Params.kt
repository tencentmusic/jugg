package com.sickworm.intellij.jugg.cmdline.base

import org.apache.log4j.Level
import java.io.File

data class Params(
    /**
     * Base build project dir to build.
     */
    val baseBuildProjectDir: File,

    /**
     * Gradle compile command, e.g. assembleDebug
     */
    val compileCommand: String,

    /**
     * Output apk relative path, supports match, e.g. app/build/outputs/debug/a*.apk
     */
    val outputApkPath: String,

    /**
     * Log level.
     */
    val logLevel: Level = Level.INFO,
)
