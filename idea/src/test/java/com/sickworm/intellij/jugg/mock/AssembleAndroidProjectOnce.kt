package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File

object AssembleAndroidProjectOnce {

    private val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
    private val gradleProjectInfoFile = JuggPathManager(projectInfo.projectRoot).gradleProjectInfoFile
    private val serializer = ProjectInfoSerializer(gradleProjectInfoFile, logger)
    private var hasAssemble = TestModeManager.isSkipTestAssemblyEnabled() && gradleProjectInfoFile.exists()
    private var projectInfoLastModified = gradleProjectInfoFile.takeIf(File::exists)?.lastModified()

    fun ensure(isNeedClean: Boolean = true) {
        logger.debug("ensure assemble, hasAssemble: $hasAssemble")
        val needAssemble = !hasAssemble || !gradleProjectInfoFile.exists()
        if (needAssemble) {
            // Only run clean on the very first assembly, not when the file was unexpectedly deleted.
            if (isNeedClean && !hasAssemble) {
                GradleBuildHelper.clean()
            }
            GradleBuildHelper.appAssembleDebug(scriptFile.absolutePath)
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
