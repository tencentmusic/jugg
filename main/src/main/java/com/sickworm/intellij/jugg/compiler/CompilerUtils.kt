package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
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
    val storeRootDir = File(PathManager.getSystemPath(), "jugg")
    // location in test: idea/build/idea-sandbox/system-test/jugg
    // location in test: idea/build/idea-sandbox/system/jugg
    // location in AS: ~/Library/Caches/Google/AndroidStudio2024.1/jugg
    val storePath = File(storeRootDir, resourcePath)
    val isTestEnv = storeRootDir.path.contains("build") && storeRootDir.path.contains("idea-sandbox")
    // isAlwaysUpdate will cause aapt2 daemon failed to start on My new MacBook :(
//    val isAlwaysUpdate = isTestEnv && !isWindows // Windows not allowed to delete it when it's running
    val isAlwaysUpdate = false
    if (storePath.exists() && !isAlwaysUpdate) {
        return storePath
    }
    storePath.parentFile.mkdirs()
    JuggCompiler::class.java.getResource(resourcePath)!!.openStream().use { ins ->
        storePath.outputStream().use { ous ->
            ins.copyTo(ous)
        }
    }
    storePath.setExecutable(true)
    return storePath
}

private val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
val isWindows = osName.contains("win")
val isLinux = listOf("nix", "nux", "aix").any { osName.contains(it) }
val isMac = osName.contains("mac")