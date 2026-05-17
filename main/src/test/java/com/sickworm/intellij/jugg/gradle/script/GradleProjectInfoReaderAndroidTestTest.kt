package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestTargetResolver
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradleProjectInfoReaderAndroidTestTest {

    // These tests exercise GradleProjectInfoReader.buildAndroidTestModuleInfo() directly
    // (a companion object function added in this task).

    private val projectDir = File("/project")
    private val appDir = File("/project/app")
    private val libraryDir = File("/project/library1")

    @Rule
    @JvmField
    val temp = TemporaryFolder()

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
    fun `buildAndroidTestModuleInfo defaults library self targeting package to namespace dot test`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = libraryModule("com.example.library1"),
            sourceDirs = listOf(File("/project/library1/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )

        assertEquals("library1.androidTest", result?.name)
        assertEquals("com.example.library1.test", result?.applicationId)
        assertEquals("com.example.library1.test", result?.instrumentationTargetPackage)
        assertEquals(listOf(ModuleDependency("library1")), result?.moduleDependencies)
    }

    @Test
    fun `library androidTest default module resolves Gradle produced self targeting apk`() {
        val e2eProjectDir = temp.newFolder("project")
        val sourceRoot = File(e2eProjectDir, "library1/src/androidTest/java")
        val sourceFile = File(sourceRoot, "com/example/library1/Library1LogicInstrumentedTest.kt").apply {
            parentFile.mkdirs()
            writeText("class Library1LogicInstrumentedTest")
        }
        val module = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = libraryModule("com.example.library1").copy(
                moduleRootDir = File(e2eProjectDir, "library1"),
                projectRootDir = e2eProjectDir,
                buildPathInfo = ModuleBuildPathInfo(e2eProjectDir, File(e2eProjectDir, "library1"), "debug"),
            ),
            sourceDirs = listOf(sourceRoot),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )!!
        val testApk = ApkInfo(
            files = listOf(ApkFileUnit("com.example.library1.test", "", true, File("library1-debug-androidTest.apk"))),
            applicationId = "com.example.library1.test",
            instrumentationTargetPackage = "com.example.library1.test",
        )

        val result = AndroidTestTargetResolver.resolve(
            sourcePath = sourceFile.path,
            projectDir = e2eProjectDir,
            modules = listOf(module),
            apks = listOf(testApk),
        )

        assertEquals(module, result.module)
        assertEquals(testApk, result.testApk)
    }
}
