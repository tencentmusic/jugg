package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import java.io.File

object AssembleAndroidProjectOnce {

    private val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
    private val gradleProjectInfoFile = JuggPathManager(projectInfo.projectRoot).gradleProjectInfoFile
    private val serializer = ProjectInfoSerializer(gradleProjectInfoFile, logger)
    private var hasAssemble = TestModeManager.isSkipTestAssemblyEnabled() && gradleProjectInfoFile.exists()
    private var projectInfoLastModified = gradleProjectInfoFile.takeIf(File::exists)?.lastModified()

    fun ensure(
        isNeedClean: Boolean = true,
        compileCommand: List<String> = listOf(":app:assembleDebug"),
        forceAssemble: Boolean = false,
    ) {
        logger.debug("ensure assemble, hasAssemble: $hasAssemble")
        val needAssemble = forceAssemble || !hasAssemble || !gradleProjectInfoFile.exists()
        if (needAssemble) {
            // Only run clean on the very first assembly, not when the file was unexpectedly deleted.
            if (isNeedClean && (!hasAssemble || forceAssemble)) {
                GradleBuildHelper.clean()
            }
            GradleBuildHelper.assembleDebug(compileCommand, scriptFile.absolutePath)
            hasAssemble = true
        }
        val lastModified = gradleProjectInfoFile.takeIf(File::exists)?.lastModified()
        if (lastModified != projectInfoLastModified) {
            serializer.clearMemoryCache()
            projectInfoLastModified = lastModified
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
