package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.dependency.LibraryDependencySet
import com.sickworm.intellij.jugg.project.dependency.UpdatedLibraryDependency
import com.sickworm.intellij.jugg.project.data.*
import groovy.json.JsonBuilder
import org.gradle.api.*
import java.io.File

class GradleDependencyDiffer(
    private val rootProject: Project,
    private val projectInfo: JuggProjectInfo,
) {

    private val pathManager = JuggPathManager(rootProject.projectDir)
    private val outputDir = pathManager.remoteDiffDir
    private val diffLibraryDir = pathManager.remoteDiffLibraryDir
    private val diffResultFile = pathManager.remoteDiffResultFile

    fun outputDiffToDir() {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        diffLibraryDir.mkdirs()

        val lastProjectInfo = getLastProjectInfo()
        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo not exists, can't diff, exit.")
            return
        } else {
            println("Jugg: Start diff libraries.")
        }

        val diffResult = DependencyDiffResult.create(projectInfo, lastProjectInfo)
        println("Jugg: Found ${diffResult.changedLibraries.size} changed libraries.")
        diffResult.changedLibraries.forEach {
            println("Jugg: ${it.oldDependency?.declaration} -> ${it.dependency?.declaration}")
        }

        val copiedDiffResult = copyAllChangedFilesToDir(diffResult, diffLibraryDir)

        val generator = ProjectInfoSerializerInGradle.getJsonGenerator()
        val builder = JsonBuilder(copiedDiffResult, generator)
        val result = builder.toString()
        diffResultFile.writeText(result)

        // write new project info
        val currentBuildChecksum = rootProject.properties[GradleProjectInfoReaderManager.PARAM_CURRENT_BUILD_CHECKSUM] as? String
        if (currentBuildChecksum.isNullOrEmpty()) {
            println("Jugg: currentBuildChecksum is null or empty, can't write new project info.")
        } else {
            val newProjectInfoFile = getProjectInfoFileByChecksum(currentBuildChecksum)
            ProjectInfoSerializerInGradle(newProjectInfoFile).save(projectInfo)
        }
    }

    fun deleteTmpProjectInfos() {
        pathManager.tmpGradleProjectInfo.deleteRecursively()
    }

    private fun copyAllChangedFilesToDir(diffResult: DependencyDiffResult, outputDir: File): DependencyDiffResult {
        return DependencyDiffResult(
            JuggProjectInfo(emptyMap()), // set to empty to reduce size
            JuggProjectInfo(emptyMap()), // set to empty to reduce size
            diffResult.addedLibraries.map {
                copyAllChangedFilesToDir(it, outputDir)
            },
            diffResult.removedLibraries.map {
                copyAllChangedFilesToDir(it, outputDir)
            },
            diffResult.updatedLibraries.map {
                copyAllChangedFilesToDir(it, outputDir)
            }
        )
    }

    private fun getLastProjectInfo(): JuggProjectInfo? {
        val lastBuildChecksum = rootProject.properties[GradleProjectInfoReaderManager.PARAM_LAST_BUILD_CHECKSUM] as? String
        val lastProjectInfoFile = getProjectInfoFileByChecksum(lastBuildChecksum)
        println("Jugg: lastBuildChecksum: $lastBuildChecksum, project infos file: $lastProjectInfoFile")
        if (!lastProjectInfoFile.exists()) {
            println("Jugg: project infos file: $lastProjectInfoFile, not exists.")
            return null
        }

        val methods = ProjectInfoSerializerInGradle::class.java.constructors
        methods.forEach {
            println("Jugg: ${it.name}")
        }
        val projectInfoSerialize = ProjectInfoSerializerInGradle(lastProjectInfoFile).load()
        if (projectInfoSerialize == null) {
            println("Jugg: project infos file: $lastProjectInfoFile parse failed.")
            return null
        }
        return JuggProjectInfoSerialize.deserialize(projectInfoSerialize)
    }

    private fun getProjectInfoFileByChecksum(checksum: String?): File {
        if (checksum.isNullOrEmpty()) {
            return pathManager.gradleProjectInfoFile
        }
        return File(pathManager.tmpGradleProjectInfo, "${checksum}_project_infos.json")
    }

    private fun copyAllChangedFilesToDir(updatedLibraryDependency: UpdatedLibraryDependency, outputDir: File): UpdatedLibraryDependency {
        return updatedLibraryDependency.copy(
            dependency = copyAllChangedFilesToDir(updatedLibraryDependency.dependency, outputDir),
            oldDependency = copyAllChangedFilesToDir(updatedLibraryDependency.oldDependency, outputDir),
        )
    }

    private fun copyAllChangedFilesToDir(libraryDependencySet: LibraryDependencySet?, outputDir: File): LibraryDependencySet? {
        libraryDependencySet ?: return null
        val dependencyFiles = libraryDependencySet.libraries.map {
            val relativePath = it.file.absolutePath
                .substringAfter(".gradle") // path after .gradle
                .replace("/.", "/") // remove hide presentation
                .replace("\\.", "\\") // remove hide presentations
            val outputFile = File(outputDir, relativePath)
            println("Jugg: copy ${it.file} to $outputFile")
            // outputFile.exists() means there is duplicate dependency, just skip it.
            if (!outputFile.exists() && it.file.exists()) {
                if (it.file.isFile) {
                    outputFile.parentFile.mkdirs()
                    it.file.copyTo(outputFile, true)
                } else if (it.file.isDirectory) {
                    outputFile.parentFile.mkdirs()
                    it.file.copyRecursively(outputFile)
                }
            }
            it.copy(file = outputFile.relativeTo(outputDir))
        }
        return libraryDependencySet.copy(libraries = dependencyFiles)
    }
}