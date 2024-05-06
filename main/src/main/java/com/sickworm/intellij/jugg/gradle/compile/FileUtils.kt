package com.sickworm.intellij.jugg.gradle.compile

import java.io.File
import java.util.zip.CRC32

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
            it.findFilesRecursively(fileNameRegex)?.let { foundFile ->
                return foundFile
            }
        }
    }
    return null
}

fun File.isChild(parent: File): Boolean {
    return this.absolutePath.replace("\\", "/")
        .startsWith(parent.absolutePath.replace("\\", "/") + "/", ignoreCase = true)
}

/** Used to generate hash of a file */
private val crc32Digest = CRC32()

val File.crc32: Long get() {
    if (!exists()) {
        return -1L
    }
    if (isDirectory) {
        return -2L
    }
    return crc32Digest.run {
        reset()
        update(readBytes())
        value
    }
}