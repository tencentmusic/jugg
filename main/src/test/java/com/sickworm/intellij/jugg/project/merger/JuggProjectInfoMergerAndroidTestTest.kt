package com.sickworm.intellij.jugg.project.merger

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JuggProjectInfoMergerAndroidTestTest {

    private val projectDir = File("/project")
    private val appDir = File("/project/app")
    private val logger: Logger = StdLogger("MergerAndroidTestTest")

    private fun androidTestGradleModule() = ModuleInfo.virtualModule.copy(
        name = "app.androidTest",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest"),
    )

    private fun androidTestIdeModule() = ModuleInfo.virtualModule.copy(
        name = "app.androidTest",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest"),
        // IDE side does not know instrumentationTargetPackage
    )

    private fun saveToTempFile(info: JuggProjectInfo): File {
        val tmpFile = Files.createTempFile("jugg_merger_test_", ".json").toFile()
        ProjectInfoSerializer(tmpFile, logger).save(info)
        return tmpFile
    }

    @Test
    fun `doMerge keeps main module debug variant when gradle androidTest shares module root`() {
        val downloadDir = File("/project/module_libs/common/download")
        val mainGradle = ModuleInfo.virtualModule.copy(
            name = "common.download",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = downloadDir,
            projectRootDir = projectDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debug"),
        )
        val androidTestGradle = ModuleInfo.virtualModule.copy(
            name = "common.download.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = downloadDir,
            projectRootDir = projectDir,
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debugAndroidTest"),
            instrumentationTargetPackage = "com.example.test",
        )
        val mainIde = mainGradle.copy(moduleType = ModuleInfo.Type.Unknown)

        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("common.download" to mainIde)))
        val gradleFile = saveToTempFile(
            JuggProjectInfo(
                mapOf(
                    "common.download" to mainGradle,
                    "common.download.androidTest" to androidTestGradle,
                )
            )
        )
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.APP)

            val merged = result.mergedInfo?.modules?.get("common.download")
            assertEquals("debug", merged?.buildVariant)
            assertEquals("debug", merged?.buildPathInfo?.buildVariant)
            assertNull(result.mergedInfo?.modules?.get("common.download.androidTest"))
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge adds gradle androidTest module when buildTarget is ANDROID_TEST`() {
        val downloadDir = File("/project/module_libs/common/download")
        val mainGradle = ModuleInfo.virtualModule.copy(
            name = "common.download",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = downloadDir,
            projectRootDir = projectDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debug"),
        )
        val androidTestGradle = ModuleInfo.virtualModule.copy(
            name = "common.download.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = downloadDir,
            projectRootDir = projectDir,
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debugAndroidTest"),
            instrumentationTargetPackage = "com.example.test",
        )
        val mainIde = mainGradle.copy(moduleType = ModuleInfo.Type.Unknown)

        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("common.download" to mainIde)))
        val gradleFile = saveToTempFile(
            JuggProjectInfo(
                mapOf(
                    "common.download" to mainGradle,
                    "common.download.androidTest" to androidTestGradle,
                )
            )
        )
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.ANDROID_TEST)
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.ANDROID_TEST)

            assertEquals("debug", result.mergedInfo?.modules?.get("common.download")?.buildVariant)
            assertNotNull(result.mergedInfo?.modules?.get("common.download.androidTest"))
            assertEquals(
                "debugAndroidTest",
                result.mergedInfo?.modules?.get("common.download.androidTest")?.buildVariant,
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge keeps IDE-only androidTest module when buildTarget is ANDROID_TEST`() {
        val mainIde = ModuleInfo.virtualModule.copy(
            name = "common.download",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleDir("common/download"),
            projectRootDir = projectDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir("common/download"), "debug"),
        )
        val extraIdeAndroidTest = ModuleInfo.virtualModule.copy(
            name = "common.fake.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleDir("common/fake"),
            projectRootDir = projectDir,
            applicationId = "com.example.fake.test",
            instrumentationTargetPackage = "com.example.fake.test",
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir("common/fake"), "debugAndroidTest"),
        )
        val mainGradle = mainIde.copy(moduleType = ModuleInfo.Type.Library)
        val realGradleAndroidTest = ModuleInfo.virtualModule.copy(
            name = "common.download.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleDir("common/download"),
            projectRootDir = projectDir,
            applicationId = "com.example.download.test",
            instrumentationTargetPackage = "com.example.download.test",
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir("common/download"), "debugAndroidTest"),
        )

        val ideFile = saveToTempFile(
            JuggProjectInfo(
                mapOf(
                    "common.download" to mainIde,
                    "common.fake.androidTest" to extraIdeAndroidTest,
                )
            )
        )
        val gradleFile = saveToTempFile(
            JuggProjectInfo(
                mapOf(
                    "common.download" to mainGradle,
                    "common.download.androidTest" to realGradleAndroidTest,
                )
            )
        )
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.ANDROID_TEST)
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.ANDROID_TEST)

            assertNotNull(result.mergedInfo?.modules?.get("common.fake.androidTest"))
            assertNotNull(result.mergedInfo?.modules?.get("common.download.androidTest"))
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge preserves instrumentationTargetPackage from gradle module`() {
        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to androidTestIdeModule())))
        val gradleFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to androidTestGradleModule())))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.ANDROID_TEST)
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.ANDROID_TEST)

            assertEquals(
                "com.example.app",
                result.mergedInfo?.modules?.get("app.androidTest")?.instrumentationTargetPackage
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge keeps IDE androidTest fields when gradle fields are missing`() {
        val ideModule = androidTestIdeModule().copy(
            applicationId = "com.example.app.test",
            instrumentationTargetPackage = "com.example.app",
        )
        val gradleModule = androidTestGradleModule().copy(
            applicationId = null,
            instrumentationTargetPackage = null,
        )
        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to ideModule)))
        val gradleFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to gradleModule)))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.ANDROID_TEST)
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.ANDROID_TEST)

            val merged = result.mergedInfo?.modules?.get("app.androidTest")
            assertEquals("com.example.app.test", merged?.applicationId)
            assertEquals("com.example.app", merged?.instrumentationTargetPackage)
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge prefers gradle androidTest fields when both sources have values`() {
        val ideModule = androidTestIdeModule().copy(
            applicationId = "com.example.ide.test",
            instrumentationTargetPackage = "com.example.ide",
        )
        val gradleModule = androidTestGradleModule().copy(
            applicationId = "com.example.gradle.test",
            instrumentationTargetPackage = "com.example.gradle",
        )
        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to ideModule)))
        val gradleFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to gradleModule)))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.ANDROID_TEST)
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.ANDROID_TEST)

            val merged = result.mergedInfo?.modules?.get("app.androidTest")
            assertEquals("com.example.gradle.test", merged?.applicationId)
            assertEquals("com.example.gradle", merged?.instrumentationTargetPackage)
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    private fun moduleDir(path: String): File = File(projectDir, path)
}
