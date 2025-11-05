package com.sickworm.intellij.jugg.cmdline.incremental

import org.apache.log4j.Level
import java.io.File

data class Params(
    /**
     * Base build project dir that has finished a gradle build.
     * [baseBuildProjectDir] and [sourceProjectDir] can be the same.
     */
    val baseBuildProjectDir: File,

    /**
     * Source project dir that contains latest source code.
     * [baseBuildProjectDir] and [sourceProjectDir] can be the same.
     */
    val sourceProjectDir: File,

    /**
     * Output apk directory.
     */
    val outputApkDir: File,

    /**
     * Changed source file to compile.
     * Strongly recommend to confirm all files are compilable or no effects to apk.
     */
    val changedFiles: List<File>,

    /**
     * Log level.
     * ALL / DEBUG / INFO / WARN / ERROR / FATAL / OFF
     */
    val logLevel: Level,
)
