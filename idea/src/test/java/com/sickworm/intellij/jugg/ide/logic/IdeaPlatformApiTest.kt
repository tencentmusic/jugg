package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.BuildNumber
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class IdeaPlatformApiTest {

    @Test
    fun `uses configured Gradle JVM when modules do not expose a Java SDK`() {
        val project = mock<Project>()
        val logger = mock<Logger>()
        val application = mock<Application>()
        val applicationInfo = mock<ApplicationInfo>()
        val moduleManager = mock<ModuleManager>()
        val gradleSettings = mock<GradleSettings>()
        val gradleJdk = mock<Sdk>()
        val linkedProjectSettings = GradleProjectSettings().apply {
            externalProjectPath = "/project"
            gradleJvm = "jbr-17"
        }

        whenever(project.basePath).thenReturn("/project")
        whenever(project.getService(ModuleManager::class.java)).thenReturn(moduleManager)
        whenever(project.getService(GradleSettings::class.java)).thenReturn(gradleSettings)
        whenever(application.getService(ApplicationInfo::class.java)).thenReturn(applicationInfo)
        whenever(applicationInfo.fullApplicationName).thenReturn("Android Studio")
        whenever(applicationInfo.build).thenReturn(BuildNumber.fromString("AI-223.7571.182"))
        whenever(applicationInfo.apiVersion).thenReturn("AI-223.7571.182")
        whenever(moduleManager.modules).thenReturn(emptyArray())
        whenever(gradleSettings.linkedProjectsSettings).thenReturn(listOf(linkedProjectSettings))
        whenever(gradleJdk.homePath).thenReturn("/ide/jbr-17")

        mockStatic(ApplicationManager::class.java).use { applicationManager ->
            applicationManager.`when`<Application> { ApplicationManager.getApplication() }
                .thenReturn(application)
            mockStatic(ExternalSystemJdkUtil::class.java).use { jdkUtil ->
                jdkUtil.`when`<Sdk> { ExternalSystemJdkUtil.getJdk(project, "jbr-17") }
                    .thenReturn(gradleJdk)

                val result = IdeaPlatformApi().getGradleJdkPath(project, logger)

                assertEquals("/ide/jbr-17", result)
            }
        }
    }
}
