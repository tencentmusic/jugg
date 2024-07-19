package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.*
import org.gradle.api.Project
import java.io.File

/**
 * Implementation of readProjectInfo.gradle.kts
 */
@Suppress("unused")
class GradleProjectInfoReaderManager(private val rootProject: Project) {

    private val juggPathManager = JuggPathManager(rootProject.rootDir)

    fun readAndSave() {
        try {
            val isDiffMode = rootProject.properties[PARAM_DIFF_MODE] == "true"
            println("Jugg: readProjectInfo.gradle execute start, diffMode: $isDiffMode")
            val startTime = System.currentTimeMillis()
            val lastProjectInfo = readLastProjectInfo()
            val projectInfo = GradleProjectInfoReader(rootProject, lastProjectInfo).getProjectInfo()

            if (isDiffMode) {
                GradleDependencyDiffer(rootProject, projectInfo).outputDiffToDir()
            } else {
                writeProjectInfoFile(projectInfo)
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

        // priority use ide project info, which contains build variant info
        if (juggPathManager.ideProjectInfoFile.exists()) {
            lastProjectInfo = juggPathManager.ideProjectInfoFile
        } else if (juggPathManager.gradleProjectInfoFile.exists()) {
            lastProjectInfo = juggPathManager.gradleProjectInfoFile
        }

        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo not exists")
            return null
        }

        return ProjectInfoSerializerInGradle(lastProjectInfo).load()
    }

    private fun writeProjectInfoFile(projectInfo: JuggProjectInfo) {
        ProjectInfoSerializerInGradle(juggPathManager.gradleProjectInfoFile).save(projectInfo)
    }

    companion object {
        const val PARAM_DIFF_MODE = "jugg.diffMode"
        const val PARAM_INC_DEPLOY_TIMES = "jugg.incDeployTimes"
    }
}