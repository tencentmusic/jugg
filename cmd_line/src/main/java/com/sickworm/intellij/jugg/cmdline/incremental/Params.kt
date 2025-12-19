package com.sickworm.intellij.jugg.cmdline.incremental

import org.apache.log4j.Level
import java.io.File

data class Params(

    /**
     * Base project dir that contains base build context.
     */
    val baseBuildJuggRootDir: File,

    /**
     * Source project dir that contains latest source code.
     */
    val sourceProjectDir: File,

    /**
     * Output apk directory. will copy all output apk to this directory.
     */
    val outputApkDir: File,

    /**
     * Changed source file to compile.
     * Strongly recommend to confirm all files are compilable or no effects to apk.
     */
    val changedFiles: List<File>,

    /**
     * Custom compiler jars.
     * @see [com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager.getCustomCompilers]
     */
    val customCompilerJars: List<File>,

    /**
     * Log level.
     * ALL / DEBUG / INFO / WARN / ERROR / FATAL / OFF
     */
    val logLevel: Level,
)
