package com.sickworm.intellij.jugg.git

import java.io.File

interface IGitManager {

    /** root dir using git */
    val rootDir: File

    /** whether [rootDir] has invoked git init */
    fun hasInit(): Boolean

    /** git init */
    fun init()

    /** remove .git folder */
    fun deleteGit()
}