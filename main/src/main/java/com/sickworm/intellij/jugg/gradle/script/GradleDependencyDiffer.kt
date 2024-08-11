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
    private val fullDiffResultFile = pathManager.remoteDiffResultWithFullFile

    fun outputDiffToDir() {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        diffLibraryDir.mkdirs()

        val lastProjectInfo = getLastProjectInfo()
        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo not exists, can't diff, exit.")
            return
        }
        val fullProjectInfo = getFullProjectInfo()
        if (fullProjectInfo == null) {
            println("Jugg: fullProjectInfo not exists, can't diff, exit.")
            return
        }
        println("Jugg: Start diff libraries.")


        // when org.gradle.configureondemand=true
        // ./gradlew --dry-run -I readProjectInfos.gradle.kts will get complete project info
        // but for ./gradlew :app:assembleDebug -I readProjectInfos.gradle.kts, will get empty project info if project won't be compiled
        // so here we filter out empty project info
        val ignoreModulesPath = lastProjectInfo.modules.filter {
            it.value.moduleType == ModuleInfo.Type.Unknown
        }.map {
            it.value.moduleRootDir.path
        }.toSet()

        // diff with last project info to show difference
        val diffResult = DependencyDiffResult.create(projectInfo, lastProjectInfo, ignoreModulesPath).copy(
            currentBuildDependencies = JuggProjectInfo(emptyMap()), // set to empty to reduce size
            lastBuildDependencies = JuggProjectInfo(emptyMap()), // set to empty to reduce size
        )
        val copiedDiffResult = copyAllChangedFilesToDir(diffResult, diffLibraryDir)
        val generator = ProjectInfoSerializerInGradle.getJsonGenerator()
        val builder = JsonBuilder(copiedDiffResult, generator)
        diffResultFile.writeText(builder.toString())

        println("Jugg: Found ${diffResult.changedLibraries.size} changed libraries.")
        diffResult.changedLibraries.forEach {
            println("Jugg: ${it.oldDependency?.declaration} -> ${it.dependency?.declaration}")
        }

        // diff with full project info to incremental compile, because we will override the last build files (multi-dex)
        val fullDiffResult = DependencyDiffResult.create(projectInfo, fullProjectInfo, ignoreModulesPath)
        val copiedFillDiffResult = copyAllChangedFilesToDir(fullDiffResult, diffLibraryDir)
        val fullBuilder = JsonBuilder(copiedFillDiffResult, generator)
        fullDiffResultFile.writeText(fullBuilder.toString())

        // write new project info
        val incDeployTimes = (rootProject.properties[GradleProjectInfoReaderManager.PARAM_INC_DEPLOY_TIMES] as? String)?.toIntOrNull()
        if (incDeployTimes == null || incDeployTimes < 0) {
            println("Jugg: incDeployTimes is null or empty, can't write new project info.")
        } else {
            val newProjectInfoFile = getWriteFileByIncDeployTimes(incDeployTimes)
            println("Jugg: write new project info to ${newProjectInfoFile}.")
            ProjectInfoSerializerInGradle(newProjectInfoFile).save(projectInfo)
        }
    }

    fun deleteTmpProjectInfos() {
        pathManager.tmpGradleProjectInfo.deleteRecursively()
    }

    private fun copyAllChangedFilesToDir(
        fullDiffResult: DependencyDiffResult,
        outputDir: File,
    ): DependencyDiffResult {
        return DependencyDiffResult(
            JuggProjectInfo(emptyMap()), // set to empty to reduce size
            JuggProjectInfo(emptyMap()), // set to empty to reduce size
            fullDiffResult.addedLibraries
                .map {
                    copyAllChangedFilesToDir(it, outputDir)
                },
            fullDiffResult.removedLibraries
                .map {
                    copyAllChangedFilesToDir(it, outputDir)
                },
            fullDiffResult.updatedLibraries
                .map {
                    copyAllChangedFilesToDir(it, outputDir)
                }
        )
    }

    private fun getLastProjectInfo(): JuggProjectInfo? {
        val incDeployTimes = (rootProject.properties[GradleProjectInfoReaderManager.PARAM_INC_DEPLOY_TIMES] as? String)?.toIntOrNull()
        return getProjectInfo(incDeployTimes)
    }

    private fun getFullProjectInfo(): JuggProjectInfo? {
        return getProjectInfo(0)
    }

    private fun getProjectInfo(incDeployTimes: Int?): JuggProjectInfo? {
        val lastProjectInfoFile = getReadFileByOrder(incDeployTimes)
        println("Jugg: incDeployTimes: $incDeployTimes, read project infos file: $lastProjectInfoFile")
        if (lastProjectInfoFile == null || !lastProjectInfoFile.exists()) {
            println("Jugg: project infos file: $lastProjectInfoFile, not exists.")
            return null
        }

        val projectInfoSerialize = ProjectInfoSerializerInGradle(lastProjectInfoFile).load()
        if (projectInfoSerialize == null) {
            println("Jugg: project infos file: $lastProjectInfoFile parse failed.")
            return null
        }
        return JuggProjectInfoSerialize.deserialize(projectInfoSerialize)
    }

    private fun getReadFileByOrder(order: Int?): File? {
        if (order == null || order <= 0) {
            return pathManager.gradleProjectInfoFile
        }

        val targetOrderFile = File(pathManager.tmpGradleProjectInfo, "project_infos_${order}.json")
        if (!targetOrderFile.exists()) {
            // incremental compile
            return getReadFileByOrder(order - 1)
        }
        return targetOrderFile
    }

    private fun getWriteFileByIncDeployTimes(order: Int): File {
        return File(pathManager.tmpGradleProjectInfo, "project_infos_${order + 1}.json")
    }

    private fun copyAllChangedFilesToDir(updatedLibraryDependency: UpdatedLibraryDependency, outputDir: File): UpdatedLibraryDependency {
        return updatedLibraryDependency.copy(
            dependency = copyAllChangedFilesToDir(updatedLibraryDependency.dependency, updatedLibraryDependency.isContentUpdate, outputDir),
            oldDependency = copyAllChangedFilesToDir(updatedLibraryDependency.oldDependency, updatedLibraryDependency.isContentUpdate, outputDir),
        )
    }

    private fun copyAllChangedFilesToDir(libraryDependencySet: LibraryDependencySet?, isContentUpdate: Boolean, outputDir: File): LibraryDependencySet? {
        libraryDependencySet ?: return null
        val dependencyFiles = libraryDependencySet.libraries.map {
            val relativePath = it.file.absolutePath
                .substringAfter(".gradle") // path after .gradle
                .replace("/.", "/") // remove hide presentation
                .replace("\\.", "\\") // remove hide presentations
            val outputFile = File(outputDir, relativePath)
            println("Jugg: copy ${it.file} to $outputFile")

            val isNeedWrite = isContentUpdate || !outputFile.exists()
            if (isNeedWrite && it.file.exists()) {
                if (it.file.isFile) {
                    outputFile.parentFile.mkdirs()
                    it.file.copyTo(outputFile, true)
                } else if (it.file.isDirectory) {
                    outputFile.parentFile.mkdirs()
                    it.file.copyRecursively(outputFile, overwrite = true)
                }
            }
            it.copy(file = outputFile.relativeTo(outputDir))
        }
        return libraryDependencySet.copy(libraries = dependencyFiles)
    }
}