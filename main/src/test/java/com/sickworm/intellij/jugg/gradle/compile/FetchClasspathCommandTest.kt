package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchClasspathCommandTest {

    @Test
    fun getRsyncArguments_whenDeleteEnabled_includesDeleteExcluded() {
        val moduleRoot = File("/tmp/jugg-project/app")
        val info = ModuleBuildPathInfo(moduleRoot.parentFile, moduleRoot, "debug")
        val arguments = FetchClasspathCommand.getRsyncArguments(listOf(info), isWindows = false)

        assertTrue(arguments.contains("--delete --delete-excluded"))
        assertTrue(arguments.contains("--prune-empty-dirs"))
        assertTrue(arguments.contains("intermediates/javac/debug/classes/**"))
    }

    @Test
    fun getRsyncArguments_whenDeleteDisabled_omitsDeleteFlags() {
        val moduleRoot = File("/tmp/jugg-project/app")
        val info = ModuleBuildPathInfo(moduleRoot.parentFile, moduleRoot, "debug")
        val arguments = FetchClasspathCommand.getRsyncArguments(
            listOf(info),
            isWindows = false,
            isNeedDeleteArg = false,
        )

        assertFalse(arguments.contains("--delete"))
        assertFalse(arguments.contains("--delete-excluded"))
    }
}
