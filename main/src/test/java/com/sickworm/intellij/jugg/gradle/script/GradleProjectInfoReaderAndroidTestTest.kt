package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GradleProjectInfoReaderAndroidTestTest {

    // These tests exercise GradleProjectInfoReader.buildAndroidTestModuleInfo() directly
    // (a companion object function added in this task).

    private val projectDir = File("/project")
    private val appDir = File("/project/app")
    private val libraryDir = File("/project/library1")

    private fun appModule(appId: String = "com.example.app") = ModuleInfo.virtualModule.copy(
        name = "app",
        moduleType = ModuleInfo.Type.Application,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        applicationId = appId,
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debug"),
    )

    private fun libraryModule(namespace: String = "com.example.library1") = ModuleInfo.virtualModule.copy(
        name = "library1",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = libraryDir,
        projectRootDir = projectDir,
        namespace = namespace,
        buildPathInfo = ModuleBuildPathInfo(projectDir, libraryDir, "debug"),
    )

    @Test
    fun `buildAndroidTestModuleInfo returns null when sourceDirs is empty`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = emptyList(),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertNull(result)
    }

    @Test
    fun `buildAndroidTestModuleInfo sets buildVariant to debugAndroidTest`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("debugAndroidTest", result?.buildVariant)
    }

    @Test
    fun `buildAndroidTestModuleInfo uses explicit testApplicationId when provided`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = "com.example.app.tests",
        )
        assertEquals("com.example.app.tests", result?.applicationId)
    }

    @Test
    fun `buildAndroidTestModuleInfo defaults applicationId to appId dot test`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("com.example.app.test", result?.applicationId)
    }

    @Test
    fun `buildAndroidTestModuleInfo sets instrumentationTargetPackage to app applicationId`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("com.example.app", result?.instrumentationTargetPackage)
    }

    @Test
    fun `buildAndroidTestModuleInfo name is appModuleName dot androidTest`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("app.androidTest", result?.name)
    }

    @Test
    fun `buildAndroidTestModuleInfo declares dependency on app module`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals(listOf(ModuleDependency("app")), result?.moduleDependencies)
    }

    @Test
    fun `buildAndroidTestModuleInfo uses namespace for self targeting library module`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = libraryModule("com.example.library1"),
            sourceDirs = listOf(File("/project/library1/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )

        assertEquals("library1.androidTest", result?.name)
        assertEquals("com.example.library1", result?.applicationId)
        assertEquals("com.example.library1", result?.instrumentationTargetPackage)
        assertEquals(listOf(ModuleDependency("library1")), result?.moduleDependencies)
    }
}
