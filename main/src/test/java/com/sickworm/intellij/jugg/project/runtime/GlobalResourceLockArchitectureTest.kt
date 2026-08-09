package com.sickworm.intellij.jugg.project.runtime

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class GlobalResourceLockArchitectureTest {

    @Test
    fun `task runner does not own global resource locks`() {
        val taskRunnerSource = repoFile(
            "main/src/main/java/com/sickworm/intellij/jugg/project/runtime/TaskRunnerManager.kt",
        ).readText()
        val executionLockSource = repoFile(
            "main/src/main/java/com/sickworm/intellij/jugg/project/runtime/ExecutionLockManager.kt",
        ).readText()
        val ideaSources = repoFile("idea/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertFalse(taskRunnerSource.contains("isGlobalWrite"))
        assertFalse(taskRunnerSource.contains("runGlobalWriteLocked"))
        assertFalse(executionLockSource.contains("fun <T> withGlobalLock"))
        ideaSources.forEach { sourceFile ->
            val source = sourceFile.readText()
            assertFalse("${sourceFile.path} should not own global resource locks", source.contains("runGlobalWriteLocked"))
            assertFalse("${sourceFile.path} should not declare global write tasks", source.contains("isGlobalWrite = true"))
            assertFalse("${sourceFile.path} should not acquire global resource locks", source.contains("withGlobalResourceLock"))
        }
    }

    private fun repoFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: error("Repository file not found: $relativePath")
        }
    }
}
