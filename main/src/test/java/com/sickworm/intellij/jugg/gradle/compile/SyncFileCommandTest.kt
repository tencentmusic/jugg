package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncFileCommandTest {

    @Test
    fun rsyncArguments_shouldOnlyKeepDotGradleJuggDirectory() {
        val arguments = SyncFileCommand.getRsyncArguments("demo")

        assertTrue(arguments.contains("--include='/demo/.gradle/'"))
        assertTrue(arguments.contains("--include='/demo/.gradle/jugg/'"))
        assertTrue(arguments.contains("--include='/demo/.gradle/jugg/**'"))
        assertTrue(arguments.contains("--include='/demo/build/jugg/config/'"))
        assertTrue(arguments.contains("--include='/demo/build/jugg/config/**'"))
        assertTrue(arguments.contains("--exclude='/demo/.gradle/**'"))
        assertFalse(arguments.contains("--include='/demo/.gradle' "))
        assertFalse(arguments.contains("--include='/demo/.gradle/jugg' "))
        assertFalse(arguments.contains("--exclude='.gradle/'"))
    }

    @Test
    fun rsyncArguments_shouldPrefixExcludePatternsWithProjectRelativePath() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf("app/src/debug/mock/**", "local-temp/", "**/*.keystore"),
        )

        assertTrue(arguments.contains("--exclude='/demo/app/src/debug/mock/**'"))
        assertTrue(arguments.contains("--exclude='/demo/local-temp/'"))
        assertTrue(arguments.contains("--exclude='/demo/**/*.keystore'"))
    }

    @Test
    fun rsyncArguments_shouldHandleProjectRootTransferWithoutDoubleSlash() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "",
            listOf("app/src/debug/mock/**"),
        )

        assertTrue(arguments.contains("--exclude='/app/src/debug/mock/**'"))
        assertFalse(arguments.contains("--exclude='//app/src/debug/mock/**'"))
    }

    @Test
    fun rsyncArguments_shouldKeepJuggRequiredIncludesBeforeUserExcludes() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf(".gradle/**", "build/**"),
        )

        assertTrue(arguments.indexOf("--include='/demo/.gradle/jugg/**'") < arguments.indexOf("--exclude='/demo/.gradle/**'"))
        assertTrue(arguments.indexOf("--include='/demo/build/jugg/config/**'") < arguments.indexOf("--exclude='/demo/build/**'"))
    }
}
