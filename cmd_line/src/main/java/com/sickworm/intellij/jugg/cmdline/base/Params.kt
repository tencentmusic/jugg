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
    val gradleCompileTask: String,

    /**
     * Output apk relative path, supports match, e.g. app/build/outputs/debug/a*.apk
     */
    val gradleOutputApkPath: String,

    /**
     * Output apk directory. will copy all output apk to this directory.
     */
    val outputApkDir: File?,

    /**
     * Log level.
     * ALL / DEBUG / INFO / WARN / ERROR / FATAL / OFF
     */
    val logLevel: Level,
)
