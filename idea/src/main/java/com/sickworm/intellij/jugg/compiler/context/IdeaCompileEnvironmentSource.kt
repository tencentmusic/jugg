package com.sickworm.intellij.jugg.compiler.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import java.io.File

/** Resolves the current IDEA Android SDK and Gradle environment on demand. */
class IdeaCompileEnvironmentSource(
    private val project: Project,
) : ICompileEnvironmentSource {
    override fun getAndroidHome(logger: Logger): File? = IdeaProjectModelSource.getAndroidSdkRootDir(logger)

    override fun buildCompileEnv(logger: Logger): List<String> = LocalGradleCompileClient.buildCompileEnv(project, logger)
}
