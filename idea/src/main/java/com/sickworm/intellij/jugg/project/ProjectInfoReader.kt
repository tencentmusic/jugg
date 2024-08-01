package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencyModel
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

class ProjectInfoReader(private val project: Project, private val logger: Logger) {

    fun printInfo() {
        val startTime = System.currentTimeMillis()
        try {
            logger.debug("Idea JVM version: ${Runtime.version().version()}")
            logger.debug("gradleDistributionUrl: ${getGradleDistributionUrl()}")
            // won't print for huge cost at init (100ms to 10s)
//            logger.debug("agpVersion: ${getAgpVersion()}")
            logger.debug("systemPath: ${File(PathManager.getSystemPath())}")
        } catch (e: Exception) {
            logger.error("printProjectInfo failed", e)
        }
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("printProjectInfo cost: $costTime ms")
    }

    private fun getGradleDistributionUrl(): String {
        val projectDir = project.basePath ?: return "[project dir not found]"
        var gradleDistributionUrl = "[file not found]"
        val gradleWrapperPropertiesFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (gradleWrapperPropertiesFile.exists()) {
            gradleDistributionUrl = gradleWrapperPropertiesFile
                .readLines()
                .find { it.startsWith("distributionUrl") }
                ?.split("=")?.get(1) ?: "[url not found]"
        }
        return gradleDistributionUrl
    }

    @Suppress("unused")
    private fun getAgpVersion(): String {
        val projectBuildModel = ProjectBuildModel.get(project)
        projectBuildModel.projectBuildModel

        var agpVersion = "[agp version not found]"
        ProjectBuildModel.get(project).projectBuildModel?.buildscript()?.dependencies()?.artifacts("classpath")
            ?.forEach { artifactDependencyModel: ArtifactDependencyModel ->
                val spec = artifactDependencyModel.spec.toString()
                if (spec.startsWith("com.android.tools.build:gradle:")) {
                    agpVersion = spec
                }
            }
        return agpVersion
    }

}