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
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest", buildDirRelativePath = ""),
    )

    private fun androidTestIdeModule() = ModuleInfo.virtualModule.copy(
        name = "app.androidTest",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest", buildDirRelativePath = ""),
        // IDE side does not know instrumentationTargetPackage
    )

    private fun saveToTempFile(info: JuggProjectInfo): File {
        val tmpFile = Files.createTempFile("jugg_merger_test_", ".json").toFile()
        ProjectInfoSerializer(tmpFile, logger).save(info)
        return tmpFile
    }

    @Test
    fun `doMerge uses primary Gradle application and its latest R jar when IDE path conflicts`() {
        val tempRoot = Files.createTempDirectory("jugg_duplicate_app_test_").toFile()
        val mainProjectDir = File(tempRoot, "main").apply { mkdirs() }
        val rootAppDir = File(mainProjectDir, "app").apply { mkdirs() }
        val includedAppDir = File(tempRoot, "SMCommon/app").apply { mkdirs() }
        val now = System.currentTimeMillis()
        createRJar(
            rootAppDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar",
            now - 10_000,
        )
        val latestRootRJar = createRJar(
            rootAppDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            now - 5_000,
        )
        createRJar(
            includedAppDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            now,
        )

        val ideApp = applicationModule(mainProjectDir, includedAppDir, ModuleInfo.Type.Unknown)
        val primaryGradleApp = applicationModule(mainProjectDir, rootAppDir, ModuleInfo.Type.Application)
        val includedGradleApp = applicationModule(mainProjectDir, includedAppDir, ModuleInfo.Type.Application)
        val ideFile = saveToTempFile(JuggProjectInfo(mapOf("app" to ideApp)))
        val primaryGradleFile = saveToTempFile(JuggProjectInfo(mapOf("app" to primaryGradleApp)))
        val includedGradleFile = saveToTempFile(JuggProjectInfo(mapOf("app" to includedGradleApp)))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)
            val result = merger.afterLocalFetch(
                listOf(
                    ProjectInfoSerializer(primaryGradleFile, logger),
                    ProjectInfoSerializer(includedGradleFile, logger),
                ),
                BuildTarget.APP,
            )

            val mergedApp = result.mergedInfo!!.modules.getValue("app")
            assertEquals(rootAppDir, mergedApp.moduleRootDir)
            assertEquals(latestRootRJar, mergedApp.buildPathInfo.rFilePath)
        } finally {
            ideFile.delete()
            primaryGradleFile.delete()
            includedGradleFile.delete()
            tempRoot.deleteRecursively()
        }
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
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debug", buildDirRelativePath = ""),
        )
        val androidTestGradle = ModuleInfo.virtualModule.copy(
            name = "common.download.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = downloadDir,
            projectRootDir = projectDir,
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debugAndroidTest", buildDirRelativePath = ""),
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
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debug", buildDirRelativePath = ""),
        )
        val androidTestGradle = ModuleInfo.virtualModule.copy(
            name = "common.download.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = downloadDir,
            projectRootDir = projectDir,
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, downloadDir, "debugAndroidTest", buildDirRelativePath = ""),
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
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir("common/download"), "debug", buildDirRelativePath = ""),
        )
        val extraIdeAndroidTest = ModuleInfo.virtualModule.copy(
            name = "common.fake.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleDir("common/fake"),
            projectRootDir = projectDir,
            applicationId = "com.example.fake.test",
            instrumentationTargetPackage = "com.example.fake.test",
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir("common/fake"), "debugAndroidTest", buildDirRelativePath = ""),
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
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir("common/download"), "debugAndroidTest", buildDirRelativePath = ""),
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

    private fun applicationModule(
        projectDir: File,
        moduleDir: File,
        type: ModuleInfo.Type,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = type,
            moduleRootDir = moduleDir,
            projectRootDir = projectDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, "debug", buildDirRelativePath = ""),
        )
    }

    private fun createRJar(moduleDir: File, relativePath: String, lastModified: Long): File {
        return File(moduleDir, relativePath).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf())
            check(setLastModified(lastModified))
        }
    }

    private fun moduleDir(path: String): File = File(projectDir, path)
}
