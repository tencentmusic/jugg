package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Before
import org.junit.Test
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
    fun testBuild() {
        val buildTestCase = listOf(
            "build.gradle" to true,
            "local.properties" to true,
            "gradle.properties" to true,
            "settings.gradle" to true,
            "app/build.gradle" to true,
            "app/src/main/aidl/ITest.aidl" to true,
            "${projectInfo.projectRootDir}/build.gradle" to false, // only detect file in project
            "app_other/build.gradle" to false, // ignore if not exists
        )

        buildTestCase.forEach { (path, result) ->
            val file = pathManager.projectDir.resolve(path)
            val isMatch = handler.filter(listOf(file)).isNotEmpty()
            assertEquals(result, isMatch, "file: $path")
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

        handler.updateBuildFileRules(rules)

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
}