package com.sickworm.intellij.jugg.ai.skills

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class JuggHookInstallerBackupCleanupTest {

    private val logger = mock(Logger::class.java)

    @Test
    fun installForClaude_shouldNotCreateBackupFileForSuccessfulReplace() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-cleanup").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText("{\"hooks\":{}}")

        val summary = JuggHookInstaller.installForClaude(userHome, logger)

        assertTrue(summary.results.all { it.status == "ok" })
        assertTrue(settingsFile.exists())
        assertFalse(File(userHome, ".claude/settings.json.bak").exists())
    }

    @Test
    fun installForClaude_shouldNotTouchExistingBackupFileWhenReplacingConfig() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-backup-sentinel").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText("{\"hooks\":{}}")
        val backupFile = File(userHome, ".claude/settings.json.bak")
        backupFile.writeText("sentinel")

        val summary = JuggHookInstaller.installForClaude(userHome, logger)

        assertTrue(summary.results.all { it.status == "ok" })
        assertTrue(backupFile.exists())
        assertTrue(backupFile.readText() == "sentinel")
    }
}
