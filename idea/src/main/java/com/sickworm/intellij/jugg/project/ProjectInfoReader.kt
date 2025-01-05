package com.sickworm.intellij.jugg.project

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import java.io.File
import java.util.jar.Manifest

class ProjectInfoReader(private val project: Project, private val logger: Logger) {

    fun printInfo() {
        val startTime = System.currentTimeMillis()
        try {
            logger.debug("plugin info: ${getPluginCompileInfo()}")
            logger.debug("os.name: ${System.getProperty("os.name")}, os.version: ${System.getProperty("os.version")}")
            logger.debug("Idea JVM version: ${Runtime.version().version()}")
            logger.debug("gradleDistributionUrl: ${getGradleDistributionUrl()}")
            logger.debug("systemPath: ${File(PathManager.getSystemPath())}")
            logger.debug("device MIN API: ${IAsDeployerCompat.MIN_DEVICE_API}")
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

    private fun getPluginCompileInfo(): String {
        val stringBuilder = StringBuilder()
        if (juggPluginInfoManifest == null) {
            stringBuilder.append("juggPluginInfoManifest not found")
        } else if (juggPluginInfoManifest?.mainAttributes.isNullOrEmpty()) {
            stringBuilder.append("juggPluginInfoManifest.mainAttributes not found")
        }
        juggPluginInfoManifest?.mainAttributes?.forEach {
            stringBuilder.append("${it.key}: ${it.value}, ")
        }
        return stringBuilder.toString()
    }

    companion object {
        val juggPluginInfoManifest: Manifest? by lazy {
            val cl = ProjectInfoReader::class.java.classLoader
            cl.getResourceAsStream("META-INF/JUGG_PLUGIN_INFO.MF")?.use {
                return@lazy Manifest(it)
            }
            null
        }
    }
}