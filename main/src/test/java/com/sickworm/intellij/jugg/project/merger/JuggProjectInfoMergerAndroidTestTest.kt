package com.sickworm.intellij.jugg.project.merger

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
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

    private fun projectInfoWithoutAgpR8(modules: Map<String, ModuleInfo>) =
        JuggProjectInfo(modules, agpR8Classpath = null)

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
        val primaryR8 = File(tempRoot, "primary/builder.jar").apply { parentFile.mkdirs(); createNewFile() }
        val includedR8 = File(tempRoot, "included/builder.jar").apply { parentFile.mkdirs(); createNewFile() }
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to ideApp)))
        val primaryGradleFile = saveToTempFile(JuggProjectInfo(
            mapOf("app" to primaryGradleApp),
            agpR8Classpath = primaryR8,
        ))
        val includedGradleFile = saveToTempFile(JuggProjectInfo(
            mapOf("app" to includedGradleApp),
            agpR8Classpath = includedR8,
        ))
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
            assertEquals(primaryR8, result.mergedInfo?.agpR8Classpath)
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

        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("common.download" to mainIde)))
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
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

        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("common.download" to mainIde)))
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
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
            projectInfoWithoutAgpR8(
                mapOf(
                    "common.download" to mainIde,
                    "common.fake.androidTest" to extraIdeAndroidTest,
                )
            )
        )
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
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
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app.androidTest" to androidTestIdeModule())))
        val gradleFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app.androidTest" to androidTestGradleModule())))
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
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app.androidTest" to ideModule)))
        val gradleFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app.androidTest" to gradleModule)))
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
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app.androidTest" to ideModule)))
        val gradleFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app.androidTest" to gradleModule)))
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

    @Test
    fun `doMerge preserves Gradle Kotlin common roots instead of IDE source directories`() {
        val commonRoots = listOf(
            File(appDir, "src/commonMain/kotlin"),
            File(appDir, "src/sharedMain/kotlin"),
        )
        val ideModule = applicationModule(projectDir, appDir, ModuleInfo.Type.Unknown).copy(
            sourceDirs = listOf(
                File(appDir, "src/commonMain/kotlin"),
                File(appDir, "src/androidMain/kotlin"),
            ),
        )
        val gradleModule = applicationModule(projectDir, appDir, ModuleInfo.Type.Application).copy(
            sourceDirs = listOf(File(appDir, "src/androidMain/kotlin")),
            kotlinCommonSourceDirs = commonRoots,
        )
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to ideModule)))
        val gradleFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to gradleModule)))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)

            val result = merger.afterLocalFetch(
                listOf(ProjectInfoSerializer(gradleFile, logger)),
                BuildTarget.APP,
            )

            val merged = result.mergedInfo?.modules?.get("app")
            assertEquals(commonRoots, merged?.kotlinCommonSourceDirs)
            assertEquals(true, merged?.sourceDirs?.containsAll(commonRoots))
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge adds dependency whose target module only exists in Gradle project info`() {
        val ideFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module("app", ModuleInfo.Type.Unknown, listOf("library1")),
                    "library1" to module("library1", ModuleInfo.Type.Unknown),
                )
            )
        )
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module(
                        "app",
                        ModuleInfo.Type.Application,
                        listOf("library1", "kmpCompose"),
                    ),
                    "library1" to module("library1", ModuleInfo.Type.Library),
                    "kmpCompose" to module("kmpCompose", ModuleInfo.Type.Library),
                )
            )
        )
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)

            val result = merger.afterLocalFetch(
                listOf(ProjectInfoSerializer(gradleFile, logger)),
                BuildTarget.APP,
            )

            assertEquals(
                listOf("library1", "kmpCompose"),
                result.mergedInfo?.modules?.get("app")?.moduleDependencies?.map { it.moduleName },
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge skips Gradle-only dependency when adding it creates a cycle`() {
        val ideFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module("app", ModuleInfo.Type.Unknown, listOf("library1")),
                    "library1" to module("library1", ModuleInfo.Type.Unknown),
                    "bridge" to module("bridge", ModuleInfo.Type.Unknown, listOf("app")),
                )
            )
        )
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module(
                        "app",
                        ModuleInfo.Type.Application,
                        listOf("library1", "cycleTarget", "safeTarget"),
                    ),
                    "library1" to module("library1", ModuleInfo.Type.Library),
                    "bridge" to module("bridge", ModuleInfo.Type.Library),
                    "cycleTarget" to module("cycleTarget", ModuleInfo.Type.Library, listOf("bridge")),
                    "safeTarget" to module("safeTarget", ModuleInfo.Type.Library),
                )
            )
        )
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)

            val result = merger.afterLocalFetch(
                listOf(ProjectInfoSerializer(gradleFile, logger)),
                BuildTarget.APP,
            )

            assertEquals(
                listOf("library1", "safeTarget"),
                result.mergedInfo?.modules?.get("app")?.moduleDependencies?.map { it.moduleName },
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge adds missing Gradle dependency when target module exists in IDE project info`() {
        val ideFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module("app", ModuleInfo.Type.Unknown, listOf("library1")),
                    "library1" to module("library1", ModuleInfo.Type.Unknown),
                    "shared" to module("shared", ModuleInfo.Type.Unknown),
                )
            )
        )
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module("app", ModuleInfo.Type.Application, listOf("library1", "shared")),
                    "library1" to module("library1", ModuleInfo.Type.Library),
                    "shared" to module("shared", ModuleInfo.Type.Library),
                )
            )
        )
        val now = System.currentTimeMillis()
        check(gradleFile.setLastModified(now - 20_000))
        check(ideFile.setLastModified(now - 10_000))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)

            val result = merger.afterLocalFetch(
                listOf(ProjectInfoSerializer(gradleFile, logger)),
                BuildTarget.APP,
            )

            assertEquals(
                listOf("library1", "shared"),
                result.mergedInfo?.modules?.get("app")?.moduleDependencies?.map { it.moduleName },
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `doMerge skips IDE-known Gradle dependency when adding it creates a cycle`() {
        val ideFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module("app", ModuleInfo.Type.Unknown, listOf("library1")),
                    "library1" to module("library1", ModuleInfo.Type.Unknown),
                    "shared" to module("shared", ModuleInfo.Type.Unknown, listOf("app")),
                )
            )
        )
        val gradleFile = saveToTempFile(
            projectInfoWithoutAgpR8(
                mapOf(
                    "app" to module("app", ModuleInfo.Type.Application, listOf("library1", "shared")),
                    "library1" to module("library1", ModuleInfo.Type.Library),
                    "shared" to module("shared", ModuleInfo.Type.Library, listOf("app")),
                )
            )
        )
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)

            val result = merger.afterLocalFetch(
                listOf(ProjectInfoSerializer(gradleFile, logger)),
                BuildTarget.APP,
            )

            assertEquals(
                listOf("library1"),
                result.mergedInfo?.modules?.get("app")?.moduleDependencies?.map { it.moduleName },
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
        }
    }

    @Test
    fun `afterSync prefers Gradle libraries for full build fallback when IDE snapshot is newer`() {
        val tempRoot = Files.createTempDirectory("jugg_full_build_fallback_test_").toFile()
        val moduleDir = File(tempRoot, "app").apply { mkdirs() }
        val oldLibraryFile = File(tempRoot, "libs/library-1.0.jar").apply {
            parentFile.mkdirs()
            writeText("old")
        }
        val newLibraryFile = File(tempRoot, "libs/library-2.0.jar").apply {
            writeText("new")
        }
        val ideModule = applicationModule(tempRoot, moduleDir, ModuleInfo.Type.Unknown).copy(
            libraryDependencies = listOf(LibraryDependency("com.example:library:1.0", oldLibraryFile)),
        )
        val gradleModule = applicationModule(tempRoot, moduleDir, ModuleInfo.Type.Application).copy(
            libraryDependencies = listOf(LibraryDependency("com.example:library:2.0", newLibraryFile)),
        )
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to ideModule)))
        val gradleFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to gradleModule)))
        val now = System.currentTimeMillis()
        check(ideFile.setLastModified(now - 20_000))
        check(gradleFile.setLastModified(now - 10_000))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)
            merger.afterLocalFetch(listOf(ProjectInfoSerializer(gradleFile, logger)), BuildTarget.APP)

            check(ideFile.setLastModified(now))
            val normalResult = merger.afterSync(
                ProjectInfoSerializer(ideFile, logger),
                BuildTarget.APP,
            )
            assertEquals(
                "com.example:library:1.0",
                normalResult.mergedInfo?.modules?.get("app")?.libraryDependencies?.single()?.name,
            )

            val result = merger.afterSync(
                ProjectInfoSerializer(ideFile, logger),
                BuildTarget.APP,
                preferGradleLibraryDependencies = true,
            )

            assertEquals(
                "com.example:library:2.0",
                result.mergedInfo?.modules?.get("app")?.libraryDependencies?.single()?.name,
            )
        } finally {
            ideFile.delete()
            gradleFile.delete()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `doMerge preserves Gradle DataBinding setting`() {
        val ideModule = applicationModule(projectDir, appDir, ModuleInfo.Type.Unknown)
        val gradleModule = applicationModule(projectDir, appDir, ModuleInfo.Type.Application).copy(
            isUseDataBinding = true,
        )
        val ideFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to ideModule)))
        val gradleFile = saveToTempFile(projectInfoWithoutAgpR8(mapOf("app" to gradleModule)))
        try {
            val merger = JuggProjectInfoMerger(logger)
            merger.afterSync(ProjectInfoSerializer(ideFile, logger), BuildTarget.APP)
            val result = merger.afterLocalFetch(
                listOf(ProjectInfoSerializer(gradleFile, logger)),
                BuildTarget.APP,
            )

            assertEquals(true, result.mergedInfo?.modules?.get("app")?.isUseDataBinding)
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

    private fun module(
        name: String,
        type: ModuleInfo.Type,
        dependencies: List<String> = emptyList(),
    ): ModuleInfo {
        val moduleDir = File(projectDir, name)
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = type,
            moduleRootDir = moduleDir,
            projectRootDir = projectDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, "debug", buildDirRelativePath = ""),
            moduleDependencies = dependencies.map(::ModuleDependency),
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
