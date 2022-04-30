package com.sickworm.intellij.jugg.git

import java.io.File

interface IGitManager {

    /**
     * root dir to use git
     */
    val rootDir: File

    /**
     * whether [rootDir] has git
     */
    fun hasInit(): Boolean

    /**
     * git init
     */
    fun init()

    /**
     * rm -rf .git
     */
    fun deleteGit()
}