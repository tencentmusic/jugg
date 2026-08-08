package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncFileCommandTest {

    @Test
    fun rsyncArguments_shouldKeepFixedBuildAndDotGradleExcludes() {
        val arguments = SyncFileCommand.getRsyncArguments("demo", emptyList())

        assertTrue(arguments.contains("--include='/demo/.gradle/'"))
        assertTrue(arguments.contains("--include='/demo/.gradle/jugg/'"))
        assertTrue(arguments.contains("--include='/demo/.gradle/jugg/**'"))
        assertTrue(arguments.contains("--include='/demo/build/jugg/config/'"))
        assertTrue(arguments.contains("--include='/demo/build/jugg/config/**'"))
        assertTrue(arguments.contains("--exclude='/demo/.gradle/**'"))
        assertTrue(arguments.contains("--exclude='/demo/build/**'"))
        assertFalse(arguments.contains("--include='/demo/.gradle' "))
        assertFalse(arguments.contains("--include='/demo/.gradle/jugg' "))
        assertFalse(arguments.contains("--exclude='.gradle/'"))
        assertTrue(arguments.contains("--exclude='build/'"))
        assertFalse(arguments.contains("--exclude='local.properties'"))
        assertFalse(arguments.contains("--exclude='.idea/'"))
        assertFalse(arguments.contains("--exclude='*.iml'"))
        assertFalse(arguments.contains("--exclude='.git/objects/'"))
        assertFalse(arguments.contains("--exclude='.git/modules/'"))
        assertFalse(arguments.contains("--exclude='.cxx/'"))
    }

    @Test
    fun rsyncArguments_shouldKeepExcludePatternsRaw() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf(".git/", "/.git/", "app/src/debug/mock/**"),
        )

        assertTrue(arguments.contains("--exclude='.git/'"))
        assertTrue(arguments.contains("--exclude='/.git/'"))
        assertTrue(arguments.contains("--exclude='app/src/debug/mock/**'"))
        assertFalse(arguments.contains("--exclude='/demo/.git/'"))
        assertFalse(arguments.contains("--exclude='.git/objects/'"))
        assertFalse(arguments.contains("--exclude='.git/modules/'"))
    }

    @Test
    fun rsyncArguments_shouldHandleProjectRootTransferWithoutDoubleSlash() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "",
            listOf("app/src/debug/mock/**"),
        )

        assertTrue(arguments.contains("--exclude='app/src/debug/mock/**'"))
    }

    @Test
    fun rsyncArguments_shouldKeepOriginalFixedFilterOrderBeforeConfigurableExcludes() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf("local.properties", ".git/objects/"),
        )

        assertTrue(arguments.indexOf("--include='/demo/.gradle/jugg/**'") < arguments.indexOf("--exclude='/demo/.gradle/**'"))
        assertTrue(arguments.indexOf("--include='/demo/build/jugg/config/**'") < arguments.indexOf("--exclude='/demo/build/**'"))
        assertTrue(arguments.indexOf("--exclude='/demo/build/**'") < arguments.indexOf("--exclude='build/'"))
        assertTrue(arguments.indexOf("--exclude='build/'") < arguments.indexOf("--exclude='local.properties'"))
    }

    @Test
    fun rsyncArguments_defaultPatternsShouldKeepLegacyFilterSequence() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf("local.properties", ".idea/", "*.iml", ".git/objects/", ".git/modules/", ".cxx/"),
        )

        val filters = Regex("--(?:include|exclude)='[^']*'")
            .findAll(arguments)
            .map { it.value }
            .toList()
        assertEquals(listOf(
            "--include='/demo/.gradle/'",
            "--include='/demo/.gradle/jugg/'",
            "--include='/demo/.gradle/jugg/**'",
            "--exclude='/demo/.gradle/**'",
            "--include='/demo/build/'",
            "--include='/demo/build/jugg/'",
            "--include='/demo/build/jugg/config/'",
            "--include='/demo/build/jugg/config/**'",
            "--include='/demo/build/jugg/database/'",
            "--include='/demo/build/jugg/database/project_infos.db/'",
            "--include='/demo/build/jugg/database/project_infos.db/project_infos.json'",
            "--exclude='/demo/build/**'",
            "--exclude='build/'",
            "--exclude='local.properties'",
            "--exclude='.idea/'",
            "--exclude='*.iml'",
            "--exclude='.git/objects/'",
            "--exclude='.git/modules/'",
            "--exclude='.cxx/'",
        ), filters)
    }
}
