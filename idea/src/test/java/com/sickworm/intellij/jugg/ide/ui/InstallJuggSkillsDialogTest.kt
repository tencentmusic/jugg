package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.logic.InstallClient
import com.sickworm.intellij.jugg.ide.logic.InstallOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class InstallJuggSkillsDialogTest {

    @Test
    fun descriptionHtml_shouldNotUseFixedBodyWidth() {
        assertFalse(InstallJuggSkillsDialog.descriptionHtml().contains("width:360px"))
    }

    @Test
    fun sectionTitles_shouldExposeAdditionalOptionsTitle() {
        assertEquals("Select agents to install:", InstallJuggSkillsDialog.selectAgentsTitle())
        assertEquals("Additional options:", InstallJuggSkillsDialog.additionalOptionsTitle())
    }

    @Test
    fun exportAndInstallSkills_shouldInstallSkillToClientConfigDir() {
        val projectDir = Files.createTempDirectory("jugg-project-manual").toFile()
        val userHome = Files.createTempDirectory("jugg-home-manual").toFile()
        val logger = mock(Logger::class.java)

        InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            options = InstallOptions(
                clients = setOf(InstallClient.CLAUDE),
                installCli = false,
                installHooks = false,
            ),
            logger = logger,
            userHome = userHome,
        )

        val skillFile = File(userHome, ".claude/skills/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
    }

    @Test
    fun exportAndInstallSkills_withNoClients_shouldNotThrow() {
        val projectDir = Files.createTempDirectory("jugg-project-manual-empty").toFile()
        val userHome = Files.createTempDirectory("jugg-home-manual-empty").toFile()
        val logger = mock(Logger::class.java)

        InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            options = InstallOptions(
                clients = emptySet(),
                installCli = false,
                installHooks = false,
            ),
            logger = logger,
            userHome = userHome,
        )
        // no exception expected
    }

    @Test
    fun exportAndInstallSkills_whenHooksSelected_shouldInstallCliAndHookScripts() {
        val projectDir = Files.createTempDirectory("jugg-project-manual-hooks").toFile()
        val userHome = Files.createTempDirectory("jugg-home-manual-hooks").toFile()
        val logger = mock(Logger::class.java)

        InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            options = InstallOptions(
                clients = emptySet(),
                installCli = false,
                installHooks = true,
            ),
            logger = logger,
            userHome = userHome,
        )

        assertTrue(File(userHome, ".jugg/bin/jugg.py").exists())
        assertTrue(File(userHome, ".jugg/hooks/start.py").exists())
        assertTrue(File(userHome, ".jugg/hooks/stop.py").exists())
        assertTrue(File(userHome, ".claude/settings.json").exists())
    }
}
