package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File

object AssembleAndroidProjectOnce {

    private var hasAssemble = false
    init {
        // backdoor for convenient testing
        if (File("${System.getProperty("user.home")}/.jugg_test_do_not_assemble").exists()) {
            hasAssemble = true
        }
    }

    private val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
    private val gradleProjectInfoFile = JuggPathManager(TestGlobal.projectRootDir).gradleProjectInfoFile
    private val serializer = ProjectInfoSerializer(gradleProjectInfoFile, logger)

    fun ensure() {
        logger.debug("ensure assemble, hasAssemble: $hasAssemble")
        if (!hasAssemble) {
            GradleBuildHelper.clean()
            GradleBuildHelper.appAssembleDebug(scriptFile.absolutePath)
        }
        hasAssemble = true
    }

    fun getProjectInfo(): JuggProjectInfo {
        ensure()
        return serializer.load()!!
    }
}