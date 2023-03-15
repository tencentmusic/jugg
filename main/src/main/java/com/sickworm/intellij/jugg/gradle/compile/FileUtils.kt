package com.sickworm.intellij.jugg.gradle.compile

import java.io.File

fun File.findFilesRecursively(fileNamePattern: String): File? {
        val fileNameRegex = Regex(
            fileNamePattern
                .replace(".", "\\.")
                .replace("*", ".*")
        )
        return findFilesRecursively(fileNameRegex)
    }

fun File.findFilesRecursively(fileNameRegex: Regex): File? {
    listFiles()?.forEach {
        if (it.isFile && it.name.matches(fileNameRegex)) {
            return it
        } else if (it.isDirectory) {
            findFilesRecursively(fileNameRegex)?.let { foundFile ->
                return foundFile
            }
        }
    }
    return null
}