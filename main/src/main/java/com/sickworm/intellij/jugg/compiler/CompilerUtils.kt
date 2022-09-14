package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.JuggManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.*

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

fun Module.guessModuleDirAdv(): File? {
    // maybe ProjectBuildModel.get(project).getModuleBuildModel(it).moduleRootDirectory is another choice
    val contentRoots = rootManager.contentRoots.filter { it.isDirectory }
    val virtualFile = contentRoots.find { name.endsWith(it.name) }
        ?: contentRoots.firstOrNull()
        ?: moduleFile?.parent
        ?: return null
    return VfsUtil.virtualToIoFile(virtualFile)
}

fun List<File>.relativePath(baseDirPath: String) = map { it.relativeTo(File(baseDirPath)) }

fun List<File>.relativePath(baseDirPath: File) = map { it.relativeTo(baseDirPath) }

fun copyResource(resourcePath: String): File {
    val storeRootDir = File(PathManager.getSystemPath(), "jugg")
    val storePath = File(storeRootDir, resourcePath)
    if (storePath.exists()) {
        return storePath
    }
    storePath.parentFile.mkdirs()
    JuggManager::class.java.getResource(resourcePath)!!.openStream().use { ins ->
        storePath.outputStream().use { ous ->
            ins.copyTo(ous)
        }
    }
    storePath.setExecutable(true)
    return storePath
}

val File.isResourceValueFile get() = parent.endsWith("values")

private val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
val isWindows = osName.contains("win")
val isLinux = listOf("nix", "nux", "aix").any { osName.contains(it) }
val isMac = osName.contains("mac")