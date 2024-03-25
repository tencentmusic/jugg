package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.project.ChangedFile

private const val KEY_JAR_DEX_FILE = "jarDexFile"

val CompileFile.jarDexFileName: String
    get() = extraInfo[KEY_JAR_DEX_FILE] as String

fun ChangedFile.withJarDexFileName(name: String): ChangedFile {
    return copy(extraInfo = extraInfo + (KEY_JAR_DEX_FILE to name))
}

fun CompileFile.withJarDexFileName(name: String): CompileFile {
    return copy(extraInfo = extraInfo + (KEY_JAR_DEX_FILE to name))
}