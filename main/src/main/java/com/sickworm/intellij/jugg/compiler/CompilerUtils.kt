package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.project.runtime.withGlobalResourceLock
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*

/**
 * CompilerUtils hosts file/process helpers shared by compiler and build-path workflows.
 */
object CompilerUtils {

    fun matchGradleDir(dirSelectInOrder: List<File>,
                       default: File = dirSelectInOrder.last(),
                       condition: (File) -> Boolean = File::exists,
    ): File {
        // 1. filter dir
        val filteredDir = dirSelectInOrder.filter {
            condition.invoke(it)
        }
        if (filteredDir.isEmpty()) {
            return default
        }
        if (filteredDir.size == 1) {
            return filteredDir.first()
        }

        // 2. choose latest create files
        return filteredDir.maxByOrNull { dir ->
            return@maxByOrNull dir.lastModified()
        }!!
    }
}

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

fun File.changeBaseDir(curBaseDir: File, newBaseDir: File, newExtension: String? = null, newName: String? = null): File {
    var relativePath = relativeTo(curBaseDir).path
    if (newName != null) {
        relativePath = relativePath.substring(0, relativePath.length - name.length) + newName
    } else if (newExtension != null) {
       relativePath = relativePath.substring(0, relativePath.length - extension.length) + newExtension
    }
    return File(newBaseDir, relativePath)
}

fun File.copyToBaseDir(curBaseDir: File, newBaseDir: File): File {
    if (curBaseDir == newBaseDir) {
        return this
    }
    val newFile = changeBaseDir(curBaseDir, newBaseDir)
    newFile.parentFile?.mkdirs()
    copyTo(newFile, overwrite = true)
    return newFile
}

fun Process.readOutput(logger: Logger) {
    val ins = BufferedReader(InputStreamReader(errorStream))
    while (true) {
        val line = ins.readLine() ?: break
        logger.warn(line)
    }
    ins.close()
}

fun List<File>.relativePathForPrintSafe(baseDirPath: File): List<File> {
    return map {
        try {
            it.relativeTo(baseDirPath)
        } catch (e: Exception) {
            it
        }
    }
}

fun copyResource(resourcePath: String): File {
    return withGlobalResourceLock("Extract runtime resource") {
        val storePath = JuggGlobalPathManager.resourceFile(resourcePath)
        if (storePath.exists()) {
            return@withGlobalResourceLock storePath
        }
        val parent = storePath.parentFile
            ?: throw IOException("Invalid Jugg resource path: $resourcePath")
        try {
            Files.createDirectories(parent.toPath())
        } catch (e: IOException) {
            throw IOException("Failed to create Jugg resource directory: ${parent.absolutePath}", e)
        }
        val tempFile = File(parent, "${storePath.name}.${UUID.randomUUID()}.tmp")
        try {
            JuggCompiler::class.java.getResource(resourcePath)!!.openStream().use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            moveResourceAtomically(tempFile, storePath)
        } finally {
            tempFile.delete()
        }
        storePath.setExecutable(true)
        storePath
    }
}

private fun moveResourceAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
val isWindows = osName.contains("win")
val isLinux = listOf("nix", "nux", "aix").any { osName.contains(it) }
val isMac = osName.contains("mac")
