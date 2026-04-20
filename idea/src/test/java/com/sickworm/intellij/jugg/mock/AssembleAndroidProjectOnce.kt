package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File

object AssembleAndroidProjectOnce {

    private var hasAssemble = false
    init {
        // backdoor for convenient testing
        if (TestModeManager.isSkipTestAssemblyEnabled()) {
            hasAssemble = true
        }
    }

    private val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
    private val gradleProjectInfoFile = JuggPathManager(projectInfo.projectRoot).gradleProjectInfoFile
    private val serializer = ProjectInfoSerializer(gradleProjectInfoFile, logger)

    fun ensure(isNeedClean: Boolean = true) {
        logger.debug("ensure assemble, hasAssemble: $hasAssemble")
        // Also re-assemble if the project info file was deleted externally (e.g. by gradlew clean),
        // unless the skip-assemble backdoor is active.
        val isSkipAssembly = TestModeManager.isSkipTestAssemblyEnabled()
        val needAssemble = !hasAssemble || (!isSkipAssembly && !gradleProjectInfoFile.exists())
        if (needAssemble) {
            // Only run clean on the very first assembly, not when the file was unexpectedly deleted.
            if (isNeedClean && !hasAssemble) {
                GradleBuildHelper.clean()
            }
            GradleBuildHelper.appAssembleDebug(scriptFile.absolutePath)
            hasAssemble = true
        }
    }

    fun forceRecompile(isNeedClean: Boolean) {
        hasAssemble = false
        ensure(isNeedClean)
    }

    fun getProjectInfo(): JuggProjectInfo {
        ensure()
        return serializer.load()!!
    }
}