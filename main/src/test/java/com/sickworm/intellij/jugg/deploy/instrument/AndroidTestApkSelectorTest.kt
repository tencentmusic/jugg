package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class AndroidTestApkSelectorTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `source anchored spec selects matching library test apk instead of first test apk`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val appTestApk = testApk("com.example.app.test", "com.example.app")
        val libraryTestApk = testApk("com.example.library1.test", "com.example.library1.test")

        val selected = AndroidTestApkSelector.select(
            spec = AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
            apks = listOf(appTestApk, libraryTestApk),
            projectDir = projectDir,
            modules = listOf(
                androidTestModule(projectDir, sourceRoot, "com.example.library1.test", "com.example.library1.test"),
            ),
        )

        assertEquals(libraryTestApk, selected)
    }

    @Test
    fun `spec without sourcePath keeps first test apk fallback for app androidTest`() {
        val appTestApk = testApk("com.example.app.test", "com.example.app")
        val libraryTestApk = testApk("com.example.library1.test", "com.example.library1.test")

        val selected = AndroidTestApkSelector.select(
            spec = AndroidTestRunSpec("com.example.FooTest", "testBar"),
            apks = listOf(appTestApk, libraryTestApk),
            projectDir = temp.newFolder("project"),
            modules = emptyList(),
        )

        assertEquals(appTestApk, selected)
    }

    private fun androidTestModule(
        projectDir: File,
        sourceRoot: File,
        applicationId: String,
        targetPackage: String,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = "library1.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = File(projectDir, "library1"),
            projectRootDir = projectDir,
            sourceDirs = listOf(sourceRoot),
            buildVariant = "debugAndroidTest",
            applicationId = applicationId,
            instrumentationTargetPackage = targetPackage,
            buildPathInfo = ModuleBuildPathInfo(projectDir, File(projectDir, "library1"), "debugAndroidTest", buildDirRelativePath = ""),
        )
    }

    private fun testApk(applicationId: String, targetPackage: String): ApkInfo {
        return ApkInfo(
            files = listOf(ApkFileUnit(applicationId, "", true, File("$applicationId.apk"))),
            applicationId = applicationId,
            instrumentationTargetPackage = targetPackage,
        )
    }
}
