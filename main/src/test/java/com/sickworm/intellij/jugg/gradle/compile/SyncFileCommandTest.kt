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
}
