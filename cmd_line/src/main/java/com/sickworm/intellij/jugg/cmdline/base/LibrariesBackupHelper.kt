package com.sickworm.intellij.jugg.cmdline.base

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LibrariesBackupHelper(
    private val pathManager: JuggPathManager,
    private val projectInfo: JuggProjectInfo,
    private val logger: Logger,
) {

    val normalizeProjectDir = pathManager.projectDir.normalize()

    fun backup(): JuggProjectInfo {
        val backupDir = pathManager.localClasspathStoragePathManager.librariesBackupDir
        backupDir.deleteRecursively()
        backupDir.mkdirs()
        val copiedModules = projectInfo.modules.mapValues { (_, moduleInfo) ->
            moduleInfo.copy(
                libraryDependencies = moduleInfo.libraryDependencies.backup(),
                runtimeLibraryDependencies = moduleInfo.runtimeLibraryDependencies.backup(),
                annotationProcessorDependencies = moduleInfo.annotationProcessorDependencies.backup(),
                kaptDependencies = moduleInfo.kaptDependencies.backup(),
                kotlinPlugins = moduleInfo.kotlinPlugins?.backupFile(),
                kotlinExtensions = moduleInfo.kotlinExtensions?.backupFile(),
                coreLibraryDesugaring = moduleInfo.coreLibraryDesugaring?.backup(),
                kspDependencies = moduleInfo.kspDependencies?.backup(),
            )
        }
        return JuggProjectInfo(copiedModules)
    }

    private fun List<LibraryDependency>.backup(): List<LibraryDependency> {
        return this.map { libraryDependency ->
            libraryDependency.copy(
                file = backup(libraryDependency.file)
            )
        }
    }

    private fun List<File>.backupFile(): List<File> {
        return this.map { backup(it) }
    }

    private val copiedMap = mutableMapOf<String, File>()

    private fun backup(file: File): File {
        copiedMap[file.path]?.let {
            return it
        }

        if (file.isChild(normalizeProjectDir)) {
            return file
        }

        val relativePath = if (file.path.startsWith(File.separator)) {
            file.path.substring(1)
        } else {
            file.path
        }
        val destFile = File(pathManager.localClasspathStoragePathManager.librariesBackupDir, relativePath)
        if (destFile.exists()) {
            return destFile
        }

        destFile.parentFile.mkdirs()
        Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        if (!destFile.exists()) {
            throw BaseBuildException("copy file failed: $file to $destFile")
        }
        copiedMap[file.path] = destFile
        return destFile
    }
}