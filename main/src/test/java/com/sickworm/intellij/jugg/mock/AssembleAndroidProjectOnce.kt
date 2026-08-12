package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File

object AssembleAndroidProjectOnce {

    val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
    val gradleProjectInfoFile = JuggPathManager(TestGlobal.projectRootDir).gradleProjectInfoFile
    private val serializer = ProjectInfoSerializer(gradleProjectInfoFile, logger)
    private var hasAssemble = TestModeManager.isSkipTestAssemblyEnabled() && gradleProjectInfoFile.exists()

    fun ensure() {
        logger.debug("ensure assemble, hasAssemble: $hasAssemble")
        if (hasAssemble && !gradleProjectInfoFile.exists()) {
            hasAssemble = false
        }
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
