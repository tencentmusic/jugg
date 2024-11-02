package com.sickworm.intellij.jugg.git

import java.io.File

/**
 * Tools to match files
 * Rule is same as .gitignore
 */
interface IFileMatcher {

    fun init(rootDir: File, rules: List<String>)

    fun isMatch(file: File, isDirectory: Boolean = false): Boolean
}