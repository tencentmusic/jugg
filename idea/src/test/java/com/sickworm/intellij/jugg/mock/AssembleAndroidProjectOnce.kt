package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File

object AssembleAndroidProjectOnce {

    private var hasAssemble = File("${System.getProperty("user.home")}/.jugg_test_do_not_assemble").exists()

    private val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
    private val gradleProjectInfoFile = JuggPathManager(projectInfo.projectRoot).gradleProjectInfoFile
    private val serializer = ProjectInfoSerializer(gradleProjectInfoFile, logger)

    fun ensure() {
        if (!hasAssemble) {
//            GradleBuildHelper.clean()
            GradleBuildHelper.appAssembleDebug(scriptFile.absolutePath)
        }
        hasAssemble = true
    }

    fun getProjectInfo(): JuggProjectInfo {
        ensure()
        return serializer.load()!!
    }
}