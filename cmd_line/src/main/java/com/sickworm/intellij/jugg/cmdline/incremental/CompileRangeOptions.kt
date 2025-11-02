package com.sickworm.intellij.jugg.cmdline.incremental

@Suppress("unused")
data class CompileRangeOptions(
    /** java files (.java) */
    val java: Boolean = true,
    /** kotlin files (.kt) */
    val kotlin: Boolean = true,
    /** resource files (.xml, .png, .jpg etc in res/) */
    val res: Boolean = true,
    /** assets files (.txt) */
    val assets: Boolean = true,
    /** so files (.so) */
    val so: Boolean = true,
    /**
     * manifest file (AndroidManifest.xml)
     * Default false for it's not that reliable.
     */
    val manifest: Boolean = false,
    /**
     * Dependencies files (*.jar *.aar, implementation 'com.google.code.gson:gson:2.8.0').
     * Default false for it's not that reliable.
     */
    val dependencies: Boolean = false,
    /**
     * If true, compiler will exit when changes happens on files that Jugg don't know how to compile.
     * Default true.
     */
    val isFailedWhenUnknownFileChanges: Boolean = true,
)