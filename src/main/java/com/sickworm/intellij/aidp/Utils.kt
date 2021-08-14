package com.sickworm.intellij.aidp

import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.vfs.VirtualFile
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

fun File.changeBaseDir(curBaseDir: File, newBaseDir: File, newExtension: String? = null, newName: String? = null): File {
    var relativePath = relativeTo(curBaseDir).path
    if (newName != null) {
        relativePath = relativePath.substring(0, relativePath.length - name.length) + newName
    } else if (newExtension != null) {
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

fun Module.guessModuleDirAdv(): VirtualFile? {
    // maybe ProjectBuildModel.get(project).getModuleBuildModel(it).moduleRootDirectory is another choice
    val contentRoots = rootManager.contentRoots.filter { it.isDirectory }
    return contentRoots.find { name.endsWith(it.name) } ?: contentRoots.firstOrNull() ?: moduleFile?.parent
}

fun List<String>.relativePath(baseDirPath: String) = map { File(it).relativeTo(File(baseDirPath)) }

fun getAndroidSdkRootDir(logger: Logger): File? {
    val allJdks = ProjectJdkTable.getInstance().allJdks
    logger.debug("all available jdks: $allJdks")
    val androidJdks = ProjectJdkTable.getInstance().allJdks.filter {
        it.name.contains("Android") && it.homeDirectory?.exists() == true
    }
    logger.debug("all available android jdks: $androidJdks")

    return androidJdks.firstOrNull()?.homeDirectory?.toIoFile()
}

fun copyResource(resourcePath: String): File {
    val storeRootDir = File(PathManager.getSystemPath(), "jugg")
    val storePath = File(storeRootDir, resourcePath)
    storePath.parentFile.mkdirs()
    AidpManager::class.java.classLoader.getResource(resourcePath)!!.openStream().use { ins ->
        storePath.outputStream().use { ous ->
            ins.copyTo(ous)
        }
    }
    storePath.setExecutable(true)
    return storePath
}

private val osName = System.getProperty("os.name").toLowerCase()
val isWindows = osName.contains("win")
val isLinux = listOf("nix", "nux", "aix").any { osName.contains(it) }
val isMac = osName.contains("mac")