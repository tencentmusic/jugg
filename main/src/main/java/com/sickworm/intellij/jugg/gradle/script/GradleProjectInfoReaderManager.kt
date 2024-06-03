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
            println("Jugg: readProjectInfo.gradle execute start")
            val startTime = System.currentTimeMillis()
            val lastProjectInfo = readLastProjectInfo()
            if (lastProjectInfo == null) {
                println("Jugg: no lastProjectInfo, exists")
                return
            }
            val projectInfo = GradleProjectInfoReader(rootProject, lastProjectInfo).getProjectInfo()
            writeProjectInfoFile(projectInfo)
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
        if (juggPathManager.projectInfoJsonFile.exists()) {
            lastProjectInfo = juggPathManager.projectInfoJsonFile
        } else if (juggPathManager.gradleProjectInfoFile.exists()) {
            lastProjectInfo = juggPathManager.gradleProjectInfoFile
        }
        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo not exists")
            return null
        }

        return ProjectInfoSerializerInGradle(lastProjectInfo) { println("Jugg: $it") }.load()
    }

    private fun writeProjectInfoFile(projectInfo: JuggProjectInfo) {
        ProjectInfoSerializerInGradle(juggPathManager.gradleProjectInfoFile) { println(it) }.save(projectInfo)
    }

}