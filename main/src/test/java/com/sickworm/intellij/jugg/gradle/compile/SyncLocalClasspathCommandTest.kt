package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class SyncLocalClasspathCommandTest {

    @Test
    fun macPathsWithSpaces_shouldExecuteSyncCommand() {
        assumeTrue(isMac)
        RsyncCompatibleHelper.init(logger)
        val tempDir = Files.createTempDirectory("jugg-sync-test").toFile()
        try {
            val sourceDir = File(tempDir, "Source Project").also { it.mkdirs() }
            val destinationDir = File(tempDir, "Classpath Backup").also { it.mkdirs() }
            val command = SyncLocalClasspathCommand(sourceDir, destinationDir, emptyList())

            assertEquals(0, CmdExecutor(logger).invoke(command))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
