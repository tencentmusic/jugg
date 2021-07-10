package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

fun File.listFilesRecursively(): List<File> {
    if (!exists()) {
        return emptyList()
    }

    if (isFile) {
        return listOf(this)
    }

    return listFiles()?.flatMap {
        it.listFilesRecursively()
    }?: emptyList()
}

fun File.clearDir() {
    listFiles()?.forEach {
        it.deleteRecursively()
    }
}

fun File.changeBaseDir(curBaseDir: File, newBaseDir: File, newExtension: String? = null): File {
    var relativePath = relativeTo(curBaseDir).path
    if (newExtension != null) {
       relativePath = relativePath.substring(0, relativePath.length - extension.length) + newExtension
    }
    return File(newBaseDir, relativePath)
}

fun Process.readOutput(logger: Logger) {
    val ins = BufferedReader(InputStreamReader(errorStream))
    while (true) {
        val line = ins.readLine() ?: break
        logger.warn(line)
    }
    ins.close()
}