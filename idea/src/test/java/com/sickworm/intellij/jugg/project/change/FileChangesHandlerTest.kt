package com.sickworm.intellij.jugg.project.change

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.project.info.ComposeResourceDirectory
import com.sickworm.intellij.jugg.project.info.ComposeResourceInfo
import com.sickworm.intellij.jugg.project.info.ComposeResourceSupportStatus
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileChangesHandlerTest {

    private val pathManager = JuggPathManager(projectInfo.projectRoot)
    private lateinit var handler: FileChangesHandler

    @Before
    fun init() {
        handler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, logger)
        handler.init(context)
    }

    @Test
    fun testSource() {
        val sourceTestCase = listOf(
            // normal source
            "app/src/main/java/com/example/myapplication/MainActivity.kt" to CompileFile.Type.Kotlin,
            "app/src/main/java/com/example/myapplication/MainActivity2.java" to CompileFile.Type.Java,
            "app/src/main/res/layout/activity_main.xml" to CompileFile.Type.Resource,
            "app/src/main/assets/test/1.jpg" to CompileFile.Type.Asset,
            "app/src/main/AndroidManifest.xml" to CompileFile.Type.AndroidManifest,
            "app_other/src/main/java/com/example/myapplication/MainActivity.kt" to null,
        )

        sourceTestCase.forEach { (path, type) ->
            val file = pathManager.projectDir.resolve(path)
            val result = handler.filter(listOf(file))
            if (type == null) {
                assertTrue(result.isEmpty(), "file: $path")
            } else {
                assertTrue(result.isNotEmpty(), "file: $path")
                assertEquals(result.first().type, type, "file: $path")
            }
        }

    }

    @Test
    fun `does not expand directories outside file change scope`() {
        val outsideDirectory = UnexpectedTraversalDirectory(
            pathManager.projectDir.parentFile.resolve("outside-file-change-scope").path
        )

        assertTrue(handler.filter(listOf(outsideDirectory)).isEmpty())
    }

    @Test
    fun `does not expand module build directories`() {
        val app = context.applicationModule
        val centralizedBuildDir = File(app.projectRootDir, "build/file-change-directory-test")
        val classpathRoot = File(pathManager.juggRootDir, "classpath/root/android_demo_project")
        val module = app.copy(
            buildPathInfo = app.buildPathInfo.copy(
                projectRootDir = classpathRoot,
                moduleRootDir = File(classpathRoot, app.moduleStdPath),
                buildDirRelativePath = centralizedBuildDir.relativeTo(app.projectRootDir).path,
            ),
        )
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertTrue(handler.filter(listOf(UnexpectedTraversalDirectory(centralizedBuildDir.path))).isEmpty())
        assertTrue(handler.filter(listOf(UnexpectedTraversalDirectory(File(app.moduleRootDir, "build").path))).isEmpty())
    }

    @Test
    fun `ignores source changes in conventional and centralized build directories`() {
        val app = context.applicationModule
        val centralizedBuildDir = File(app.projectRootDir, "build/file-change-source-test")
        val conventionalSourceDir = File(app.moduleRootDir, "build/generated/source")
        val centralizedSourceDir = File(centralizedBuildDir, "generated/source")
        val classpathRoot = File(pathManager.juggRootDir, "classpath/root/android_demo_project")
        val module = app.copy(
            sourceDirs = app.sourceDirs + conventionalSourceDir + centralizedSourceDir,
            buildPathInfo = app.buildPathInfo.copy(
                projectRootDir = classpathRoot,
                moduleRootDir = File(classpathRoot, app.moduleStdPath),
                buildDirRelativePath = centralizedBuildDir.relativeTo(app.projectRootDir).path,
            ),
        )
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        withTemporaryFile(File(conventionalSourceDir, "ConventionalGenerated.kt")) {
            withTemporaryFile(File(centralizedSourceDir, "CentralizedGenerated.kt")) {
                assertTrue(handler.filter(listOf(File(conventionalSourceDir, "ConventionalGenerated.kt"))).isEmpty())
                assertTrue(handler.filter(listOf(File(centralizedSourceDir, "CentralizedGenerated.kt"))).isEmpty())
            }
        }
    }

    @Test
    fun `expands directories of modules outside the project directory`() {
        val externalModuleDir = Files.createTempDirectory("jugg-external-module").toFile()
        try {
            val sourceDir = externalModuleDir.resolve("src/main/java")
            val sourceFile = sourceDir.resolve("ExternalSource.kt")
            sourceDir.mkdirs()
            sourceFile.createNewFile()

            val compileContext = context
            val externalModule = compileContext.applicationModule.copy(
                name = "external",
                moduleRootDir = externalModuleDir,
                projectRootDir = externalModuleDir.parentFile,
                sourceDirs = listOf(sourceDir),
                resourceDirs = emptyList(),
                assetsDirs = emptyList(),
                manifestFile = null,
                buildPathInfo = ModuleBuildPathInfo(
                    projectRootDir = externalModuleDir.parentFile,
                    moduleRootDir = externalModuleDir,
                    buildVariant = compileContext.applicationModule.buildVariant,
                    buildDirRelativePath = "",
                ),
            )
            handler.init(compileContext.copy(modules = mapOf(externalModule.name to externalModule)))

            val changedFile = handler.filter(listOf(externalModuleDir)).single()

            assertEquals(sourceFile, changedFile.file)
            assertEquals(externalModule.name, changedFile.module.name)
        } finally {
            externalModuleDir.deleteRecursively()
        }
    }

    @Test
    fun testComposeResource() {
        val app = context.applicationModule
        val composeInfo = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "com.example.test.resources",
            publicResClass = true,
            resourceDirectories = listOf(
                ComposeResourceDirectory("commonMain", File(app.moduleRootDir, "src/main/composeResources")),
                ComposeResourceDirectory("androidMain", File(app.moduleRootDir, "src/main/customComposeResources")),
            ),
            assetRelativePath = "composeResources/com.example.test.resources",
        )
        val module = app.copy(composeResourceInfo = composeInfo)
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFile(
            path = "app/src/main/composeResources/values/strings.xml",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/main/composeResources",
        )
        assertChangedFile(
            path = "app/src/main/customComposeResources/drawable/android_icon.png",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/main/customComposeResources",
        )
    }

    @Test
    fun `detects first files created under configured missing Compose roots`() {
        val app = context.applicationModule
        val defaultRoot = File(app.moduleRootDir, "src/newCommonMain/composeResources")
        val customRoot = File(app.moduleRootDir, "src/newAndroidMain/customComposeResources")
        defaultRoot.deleteRecursively()
        customRoot.deleteRecursively()
        val module = app.copy(composeResourceInfo = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "com.example.test.resources",
            publicResClass = true,
            resourceDirectories = listOf(
                ComposeResourceDirectory("commonMain", defaultRoot),
                ComposeResourceDirectory("androidMain", customRoot),
            ),
            assetRelativePath = "composeResources/com.example.test.resources",
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertTrue(!defaultRoot.exists())
        assertTrue(!customRoot.exists())
        assertChangedFile(
            path = "app/src/newCommonMain/composeResources/values/first.xml",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/newCommonMain/composeResources",
        )
        assertChangedFile(
            path = "app/src/newAndroidMain/customComposeResources/files/first.txt",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/newAndroidMain/customComposeResources",
        )
    }

    @Test
    fun `keeps unsupported Compose changes in the incremental compile input`() {
        val app = context.applicationModule
        val root = File(app.moduleRootDir, "src/unsupportedMain/composeResources")
        val module = app.copy(composeResourceInfo = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "",
            publicResClass = false,
            resourceDirectories = listOf(ComposeResourceDirectory("commonMain", root)),
            assetRelativePath = "",
            supportStatus = ComposeResourceSupportStatus.Unsupported,
            unsupportedReason = "Unsupported Compose resource metadata",
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFile(
            path = "app/src/unsupportedMain/composeResources/values/first.xml",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/unsupportedMain/composeResources",
        )
    }

    @Test
    fun testBuild() {
        withTemporaryFile("local.properties") {
            withTemporaryFile("app/src/main/aidl/ITest.aidl") {
                val buildTestCase = listOf(
                    "build.gradle" to true,
                    "local.properties" to true,
                    "gradle.properties" to true,
                    "settings.gradle" to true,
                    "app/build.gradle" to true,
                    "app/src/main/aidl/ITest.aidl" to true,
                    "../build.gradle" to true, // root build file is part of the IDE project
                    "app_other/build.gradle" to false, // ignore if not exists
                )

                buildTestCase.forEach { (path, result) ->
                    val file = pathManager.projectDir.resolve(path)
                    val isMatch = handler.filter(listOf(file)).isNotEmpty()
                    assertEquals(result, isMatch, "file: $path")
                }
            }
        }
    }

    @Test
    fun testCustomBuildRules() {
        val rules = """
            dependency.yaml
            /dependency_root.yaml
            **/mologtag/*
            *.config
            !ci.config
        """.trimIndent().split("\n").toList()

        val buildTestCase = listOf(
            "dependency.yaml" to true,
            "app/dependency.yaml" to true,
            "dependency_root.yaml" to true,
            "app/dependency_root.yaml" to false,
            "mologtag/config.yaml" to true,
            "app/mologtag/config.yaml" to true,
            "custom.config" to true,
            "app/custom.config" to true,
            "ci.config" to false,
        )

        // for convenience, create test file and delete after test
        buildTestCase.forEach { (path, _) ->
            val file = pathManager.projectDir.resolve(path)
            file.parentFile.mkdirs()
            file.createNewFile()
        }

        // failed before update rules
        buildTestCase.forEach { (path, _) ->
            val file = pathManager.projectDir.resolve(path)

            val isMatch = handler.filter(listOf(file)).isNotEmpty()
            assertEquals(false, isMatch, "file: $path")
        }

        handler.updateBuildFileRules(rules, emptyList())

        // pass after update rules
        buildTestCase.forEach { (path, result) ->
            val file = pathManager.projectDir.resolve(path)
            val isMatch = handler.filter(listOf(file)).isNotEmpty()
            assertEquals(result, isMatch, "file: $path")
        }
        // also check normal build
        testBuild()

        // for convenience, create test file and delete after test
        buildTestCase.forEach { (path, _) ->
            val file = pathManager.projectDir.resolve(path)
            file.delete()
            if (file.parentFile.listFiles().isNullOrEmpty()) {
                file.parentFile.delete()
            }
        }
    }

    private fun withTemporaryFile(path: String, block: () -> Unit) {
        withTemporaryFile(pathManager.projectDir.resolve(path), block)
    }

    private fun withTemporaryFile(file: File, block: () -> Unit) {
        val existed = file.exists()
        val missingParents = generateSequence(file.parentFile) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
        if (!existed) {
            file.parentFile.mkdirs()
            file.createNewFile()
        }
        try {
            block()
        } finally {
            if (!existed) {
                file.delete()
                missingParents.forEach(File::delete)
            }
        }
    }

    private fun assertChangedFile(path: String, expectedType: CompileFile.Type, expectedBaseDir: String) {
        withTemporaryFile(path) {
            val changed = handler.filter(listOf(pathManager.projectDir.resolve(path))).single()
            assertEquals(expectedType, changed.type)
            assertTrue(changed.type != CompileFile.Type.Resource)
            assertTrue(changed.type != CompileFile.Type.Asset)
            assertEquals(pathManager.projectDir.resolve(expectedBaseDir).canonicalFile, changed.baseDir.canonicalFile)
            assertEquals(context.applicationModule.name, changed.module.name)
        }
    }

    private class UnexpectedTraversalDirectory(path: String) : File(path) {
        override fun isDirectory() = true

        override fun listFiles(): Array<File> {
            error("Directory outside file change scope should not be expanded")
        }
    }

}
