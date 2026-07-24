package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchClasspathCommandTest {

    @Test
    fun getRsyncArguments_whenDeleteEnabled_includesDeleteExcluded() {
        val moduleRoot = File("/tmp/jugg-project/app")
        val info = ModuleBuildPathInfo(moduleRoot.parentFile, moduleRoot, "debug", buildDirRelativePath = "")
        val arguments = FetchClasspathCommand.getRsyncArguments(listOf(info), isWindows = false)

        assertTrue(arguments.contains("--delete --delete-excluded"))
        assertTrue(arguments.contains("--prune-empty-dirs"))
        assertTrue(arguments.contains("intermediates/javac/debug/classes/**"))
    }

    @Test
    fun getRsyncArguments_whenDeleteDisabled_omitsDeleteFlags() {
        val moduleRoot = File("/tmp/jugg-project/app")
        val info = ModuleBuildPathInfo(moduleRoot.parentFile, moduleRoot, "debug", buildDirRelativePath = "")
        val arguments = FetchClasspathCommand.getRsyncArguments(
            listOf(info),
            isWindows = false,
            isNeedDeleteArg = false,
        )

        assertFalse(arguments.contains("--delete"))
        assertFalse(arguments.contains("--delete-excluded"))
    }

    @Test
    fun getRsyncArguments_customBuildDirectory_usesProjectRelativePath() {
        val projectRoot = File("/tmp/jugg-project")
        val moduleRoot = File(projectRoot, "app")
        val info = ModuleBuildPathInfo(
            projectRoot,
            moduleRoot,
            "debug",
            buildDirRelativePath = "build/app",
        )

        val arguments = FetchClasspathCommand.getRsyncArguments(listOf(info), isWindows = false)

        assertTrue(arguments.contains("build/app/intermediates/javac/debug/classes/**"))
        assertFalse(arguments.contains("../build/app"))
    }

    @Test
    fun getRsyncArguments_manyConventionalModules_deduplicatesBuildRules() {
        val projectRoot = File("/tmp/jugg-project")
        val modules = (0 until 200).map { index ->
            val moduleRoot = File(projectRoot, "module-$index")
            ModuleBuildPathInfo(
                projectRoot,
                moduleRoot,
                "debug",
                buildDirRelativePath = "module-$index/build",
            )
        }

        val arguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows = false)

        assertEquals(1, arguments.countOccurrences("--include='build/intermediates/javac/debug/classes/**'"))
        assertFalse(arguments.contains("module-0/build/intermediates/javac/debug/classes/**"))
        assertTrue(arguments.length < 10_000, "conventional module filters should not grow with module count")
    }

    @Test
    fun getRsyncArguments_conventionalModules_preservesDifferentVariants() {
        val projectRoot = File("/tmp/jugg-project")
        val debugModule = File(projectRoot, "debug-module")
        val releaseModule = File(projectRoot, "release-module")
        val modules = listOf(
            ModuleBuildPathInfo(projectRoot, debugModule, "debug", buildDirRelativePath = "debug-module/build"),
            ModuleBuildPathInfo(projectRoot, releaseModule, "release", buildDirRelativePath = "release-module/build"),
        )

        val arguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows = false)

        assertTrue(arguments.contains("--include='build/tmp/kotlin-classes/debug/**'"))
        assertTrue(arguments.contains("--include='build/tmp/kotlin-classes/release/**'"))
    }

    @Test
    fun getRsyncArguments_mixedBuildDirectories_keepsCustomAndConfiguredPathsExact() {
        val projectRoot = File("/tmp/jugg-project")
        val conventionalModule = File(projectRoot, "app")
        val customModule = File(projectRoot, "feature")
        val modules = listOf(
            ModuleBuildPathInfo(
                projectRoot,
                conventionalModule,
                "debug",
                customClasspath = listOf("build/custom/classpath.jar"),
                customSyncFilePath = listOf("custom/generated"),
                buildDirRelativePath = "app/build",
            ),
            ModuleBuildPathInfo(
                projectRoot,
                customModule,
                "debug",
                buildDirRelativePath = "build/feature",
            ),
        )

        val arguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows = false)

        assertTrue(arguments.contains("--include='build/intermediates/javac/debug/classes/**'"))
        assertTrue(arguments.contains("--include='build/feature/intermediates/javac/debug/classes/**'"))
        assertTrue(arguments.contains("--include='app/build/custom/classpath.jar'"))
        assertTrue(arguments.contains("--include='app/custom/generated/**'"))
    }

    private fun String.countOccurrences(value: String): Int = split(value).size - 1
}
