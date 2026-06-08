package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class RequestedLibraryTestApkPlannerTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `resolve self targeting library module from owner androidTest source before merge`() {
        val projectDir = temp.newFolder("project")
        val moduleRoot = projectDir.resolve("library1").apply { mkdirs() }
        val sourceFile = moduleRoot.resolve("src/androidTest/kotlin/FooTest.kt").apply {
            parentFile.mkdirs()
            writeText("class FooTest")
        }
        val ownerModule = module("library1", projectDir, moduleRoot, "debug")

        val resolved = RequestedLibraryTestApkPlanner.resolveSelfTargetingLibraryModule(
            AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
            projectDir,
            listOf(ownerModule),
        )

        assertEquals("library1.androidTest", resolved?.name)
        assertEquals("debugAndroidTest", resolved?.buildVariant)
    }

    @Test
    fun `plan derives current library androidTest task from owner source`() {
        val projectDir = temp.newFolder("project")
        val moduleRoot = projectDir.resolve("library1").apply { mkdirs() }
        val sourceFile = moduleRoot.resolve("src/androidTest/kotlin/FooTest.kt").apply {
            parentFile.mkdirs()
            writeText("class FooTest")
        }

        val plan = RequestedLibraryTestApkPlanner.plan(
            AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
            projectDir,
            listOf(module("library1", projectDir, moduleRoot, "debug")),
        )

        assertEquals(":library1:assembleDebugAndroidTest", plan?.gradleTask)
        assertEquals("library1/build/outputs/apk/androidTest/debug/*.apk", plan?.outputApkPattern)
    }

    private fun module(
        name: String,
        projectDir: File,
        moduleRoot: File,
        buildVariant: String,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleRoot,
            projectRootDir = projectDir,
            sourceDirs = listOf(moduleRoot.resolve("src/main/kotlin")),
            buildVariant = buildVariant,
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleRoot, buildVariant),
            applicationId = "com.example.library.test",
        )
    }
}
