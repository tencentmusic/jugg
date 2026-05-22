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
            val merger = JuggProjectInfoMerger(logger) { BuildTarget.APP }
            merger.afterSync(ProjectInfoSerializer(ideFile, logger))
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)))

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
            val merger = JuggProjectInfoMerger(logger) { BuildTarget.ANDROID_TEST }
            merger.afterSync(ProjectInfoSerializer(ideFile, logger))
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)))

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
    fun `doMerge preserves instrumentationTargetPackage from gradle module`() {
        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to androidTestIdeModule())))
        val gradleFile = saveToTempFile(JuggProjectInfo(mapOf("app.androidTest" to androidTestGradleModule())))
        try {
            val merger = JuggProjectInfoMerger(logger) { BuildTarget.ANDROID_TEST }
            merger.afterSync(ProjectInfoSerializer(ideFile, logger))
            val result = merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)))

            assertEquals(
                "com.example.app",
                result.mergedInfo?.modules?.get("app.androidTest")?.instrumentationTargetPackage
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }
}
