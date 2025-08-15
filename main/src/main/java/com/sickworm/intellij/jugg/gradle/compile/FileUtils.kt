package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    val isCaseInsensitiveOs = isWindows || isMac // Windows and Mac are case insensitive
    return this.absolutePath.replace("\\", "/")
        .startsWith(parent.absolutePath.replace("\\", "/") + "/", ignoreCase = isCaseInsensitiveOs)
}

fun File.pathEquals(other: File?): Boolean {
    if (other == null) return false
    val isCaseInsensitiveOs = isWindows || isMac // Windows and Mac are case insensitive
    return this.path.replace("\\", "/").equals(
        other.path.replace("\\", "/"),
        ignoreCase = isCaseInsensitiveOs,
    )
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

fun File.zipFiles(files: List<File>, parentPath: String = "") {
    val zipFile = this
    zipFile.parentFile?.mkdirs()
    if (zipFile.exists()) {
        zipFile.delete()
    }
    // zip using java.util.zip
    val zipOutputStream = ZipOutputStream(zipFile.outputStream())
    files.forEach {
        val zipEntry = ZipEntry(parentPath + it.name)
        zipOutputStream.putNextEntry(zipEntry)
        zipOutputStream.write(it.readBytes())
        zipOutputStream.closeEntry()
    }
    zipOutputStream.close()
}