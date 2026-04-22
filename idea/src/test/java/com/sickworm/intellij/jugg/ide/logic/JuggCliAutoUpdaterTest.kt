package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class JuggCliAutoUpdaterTest {

    private val logger = mock(Logger::class.java)

    @Before
    fun setUp() {
        JuggCliAutoUpdater.resetForTest()
    }

    @Test
    fun readVersionFromZip_returnsCorrectVersion() {
        val version = JuggCliAutoUpdater.readVersionFromZip()
        assertNotNull("Should read CLI_VERSION from bundled zip", version)
        assertTrue("Version should not be blank", version!!.isNotBlank())
        assertTrue("Version should match semver pattern", version.matches(Regex("\\d+\\.\\d+.*")))
    }

    @Test
    fun readVersionFromLocal_returnsCorrectVersion() {
        val skillDir = Files.createTempDirectory("jugg-skill").toFile()
        val skillMd = File(skillDir, "SKILL.md")
        skillMd.writeText("""
            ---
            name: jugg-android-dev-loop
            version: 2.3.1
            date: 2026-04-22
            description: test skill
            ---
        """.trimIndent())

        val version = JuggCliAutoUpdater.readVersionFromLocal(skillDir)
        assertEquals("2.3.1", version)
    }

    @Test
    fun readVersionFromLocal_returnsNullWhenFileAbsent() {
        val skillDir = Files.createTempDirectory("jugg-skill-empty").toFile()
        val version = JuggCliAutoUpdater.readVersionFromLocal(skillDir)
        assertNull("Should return null when file is absent", version)
    }

    @Test
    fun detectInstalledClients_detectsExistingDirs() {
        val userHome = Files.createTempDirectory("jugg-home").toFile()
        // Only create codex and codebuddy skill dirs
        File(userHome, ".codex/skills/jugg-android-dev-loop").mkdirs()
        File(userHome, ".codebuddy/skills/jugg-android-dev-loop").mkdirs()

        val clients = JuggCliAutoUpdater.detectInstalledClients(userHome)

        assertTrue(InstallClient.CODEX in clients)
        assertTrue(InstallClient.CODEBUDDY in clients)
        assertFalse(InstallClient.CLAUDE in clients)
        assertFalse(InstallClient.GEMINI in clients)
        assertFalse(InstallClient.CURSOR in clients)
    }

    @Test
    fun detectInstalledClients_detectsCursorWhenSkillDirExists() {
        val userHome = Files.createTempDirectory("jugg-home-cursor-detect").toFile()
        File(userHome, ".cursor/skills/jugg-android-dev-loop").mkdirs()

        val clients = JuggCliAutoUpdater.detectInstalledClients(userHome)
        assertTrue(InstallClient.CURSOR in clients)
    }

    @Test
    fun detectInstalledClients_doesNotDetectCursorWhenSkillDirAbsent() {
        val userHome = Files.createTempDirectory("jugg-home-cursor-absent").toFile()
        val clients = JuggCliAutoUpdater.detectInstalledClients(userHome)
        assertFalse(InstallClient.CURSOR in clients)
    }

    @Test
    fun detectInstalledClients_returnsEmptyWhenNoneInstalled() {
        val userHome = Files.createTempDirectory("jugg-home-empty").toFile()
        val clients = JuggCliAutoUpdater.detectInstalledClients(userHome)
        assertTrue("Should detect no clients when dirs are absent", clients.isEmpty())
    }

    @Test
    fun checkAndUpdate_skipsWhenBinDirAbsent() {
        val userHome = Files.createTempDirectory("jugg-home-nobin").toFile()
        // binDir does NOT exist
        JuggCliAutoUpdater.checkAndUpdate(logger, userHome)
        // No exception, no crash = pass
        assertFalse("binDir should not be created", File(userHome, ".jugg/bin").exists())
    }

    @Test
    fun checkAndUpdate_skipsWhenAlreadyLatest() {
        val userHome = Files.createTempDirectory("jugg-home-latest").toFile()
        File(userHome, ".jugg/bin").mkdirs()
        // Write local SKILL.md with same version as bundled into jugg skills dir
        val bundledVersion = JuggCliAutoUpdater.readVersionFromZip() ?: "1.0.0"
        val juggSkillDir = File(userHome, ".jugg/skills/jugg-android-dev-loop").also { it.mkdirs() }
        File(juggSkillDir, "SKILL.md").writeText("---\nname: jugg-android-dev-loop\nversion: $bundledVersion\ndate: 2026-01-01\ndescription: test\n---\n")

        val sentinel = File(userHome, ".jugg/bin/sentinel.txt")
        sentinel.writeText("original")

        JuggCliAutoUpdater.checkAndUpdate(logger, userHome)

        assertEquals("Bin dir should not be modified when already latest",
            "original", sentinel.readText())
    }

    @Test
    fun checkAndUpdate_updatesWhenOutdated() {
        val userHome = Files.createTempDirectory("jugg-home-outdated").toFile()
        File(userHome, ".jugg/bin").mkdirs()
        // Write old version SKILL.md into jugg skills dir
        val juggSkillDir = File(userHome, ".jugg/skills/jugg-android-dev-loop").also { it.mkdirs() }
        File(juggSkillDir, "SKILL.md").writeText("---\nname: jugg-android-dev-loop\nversion: 0.0.1\ndate: 2026-01-01\ndescription: test\n---\n")

        JuggCliAutoUpdater.checkAndUpdate(logger, userHome)

        // After update, SKILL.md in jugg skills dir should have bundled version
        val updatedVersion = JuggCliAutoUpdater.readVersionFromLocal(juggSkillDir)
        val bundledVersion = JuggCliAutoUpdater.readVersionFromZip()
        assertEquals("Version should match bundled after update", bundledVersion, updatedVersion)
    }

    @Test
    fun checkAndUpdate_updatesSkillWhenClientInstalled() {
        val userHome = Files.createTempDirectory("jugg-home-skill-update").toFile()
        File(userHome, ".jugg/bin").mkdirs()
        val juggSkillDir = File(userHome, ".jugg/skills/jugg-android-dev-loop").also { it.mkdirs() }
        File(juggSkillDir, "SKILL.md").writeText("---\nname: jugg-android-dev-loop\nversion: 0.0.1\ndate: 2026-01-01\ndescription: test\n---\n")

        // Pre-install a skill dir for codex
        val skillDir = File(userHome, ".codex/skills/jugg-android-dev-loop")
        skillDir.mkdirs()
        File(skillDir, "old_file.txt").writeText("old")

        JuggCliAutoUpdater.checkAndUpdate(logger, userHome)

        // Skill should be updated (SKILL.md from zip should now exist)
        assertTrue("SKILL.md should exist after skill update",
            File(userHome, ".codex/skills/jugg-android-dev-loop/SKILL.md").exists())
    }
}
