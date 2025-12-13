package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.*
import org.gradle.api.Project
import org.gradle.api.initialization.IncludedBuild
import java.io.File

/**
 * Implementation of readProjectInfo.gradle.kts
 */
@Suppress("unused")
class GradleProjectInfoReaderManager(
    private val rootProject: Project,
    private val includeBuildProjects: Collection<IncludedBuild>,
) {

    private val juggPathManager = JuggPathManager(rootProject.rootDir)

    fun readAndSave() {
        try {
            val isDiffMode = rootProject.properties[PARAM_DIFF_MODE] == "true"
            println("Jugg: readProjectInfo.gradle execute start, diffMode: $isDiffMode, " +
                    "includeBuildProjects: ${includeBuildProjects.map { it.projectDir }}")
            val startTime = System.currentTimeMillis()
            val lastProjectInfo = readLastProjectInfo()
            val projectInfo = GradleProjectInfoReader(rootProject, lastProjectInfo).getProjectInfo()

            if (isDiffMode) {
                GradleDependencyDiffer(rootProject, projectInfo).outputDiffToDir()
            } else {
                writeProjectInfoFile(projectInfo)
                writeIncludeProjectsFile()
                GradleDependencyDiffer(rootProject, projectInfo).deleteTmpProjectInfos()
            }

            val costTime = System.currentTimeMillis() - startTime
            println("Jugg: readProjectInfo.gradle execute success, cost: ${costTime}ms")
        } catch (e: Throwable) {
            println("Jugg: readProjectInfo.gradle execute failed: $e")
            printException(e)
        }
    }

    /**
     * We need this to determined build variant, the info is from IDE
     */
    private fun readLastProjectInfo(): JuggProjectInfoSerialize?  {
        var lastProjectInfo: File? = null

        if (juggPathManager.gradleProjectInfoFile.exists()) {
            lastProjectInfo = juggPathManager.gradleProjectInfoFile
        }
        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo ${juggPathManager.gradleProjectInfoFile} not exists")
            return null
        }

        val lastProjectInfoSerialize = ProjectInfoSerializerInGradle(lastProjectInfo).load()
        if (lastProjectInfoSerialize == null) {
            println("Jugg: lastProjectInfo ${juggPathManager.gradleProjectInfoFile} load failed")
            return null
        }
        return lastProjectInfoSerialize
    }

    private fun writeProjectInfoFile(projectInfo: JuggProjectInfo) {
        ProjectInfoSerializerInGradle(juggPathManager.gradleProjectInfoFile).save(projectInfo)
    }

    private fun writeIncludeProjectsFile() {
        val includeProjectsFile = juggPathManager.gradleIncludeBuildsFile
        if (includeBuildProjects.isEmpty()) {
            if (includeProjectsFile.exists()) includeProjectsFile.delete()
        } else {
            val projectFiles = includeBuildProjects.mapIndexed { index, it ->
                val originFile = JuggPathManager(it.projectDir).gradleProjectInfoFile
                val targetFile = File(includeProjectsFile.parentFile, "include_build_${index + 1}_gradle_project_infos.json")
                originFile.copyTo(targetFile, true)
                targetFile
            }.joinToString("\n")
            includeProjectsFile.parentFile.mkdirs()
            includeProjectsFile.writeText(projectFiles)
        }
    }

    companion object {
        const val PARAM_DIFF_MODE = "jugg.diffMode"
        const val PARAM_INC_DEPLOY_TIMES = "jugg.incDeployTimes"
    }
}