package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.skills.InstallClient
import com.sickworm.intellij.jugg.ai.skills.InstallOptions
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
        assertEquals("Additional options: (Recommended)", InstallJuggSkillsDialog.additionalOptionsTitle())
        assertEquals("~/.jugg/skills/hooks", InstallJuggSkillsDialog.hooksInstallPathHint())
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

        val setupGuide = InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            options = InstallOptions(
                clients = emptySet(),
                installCli = false,
                installHooks = false,
            ),
            logger = logger,
            userHome = userHome,
        )
        assertEquals(File(userHome, ".jugg/skills/install/agent_setup.md").path, setupGuide.path)
    }

    @Test
    fun exportAndInstallSkills_whenHooksSelected_shouldInstallCliAndUseBundledHookScripts() {
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
        assertTrue(File(userHome, ".jugg/skills/hooks/start.py").exists())
        assertTrue(File(userHome, ".jugg/skills/hooks/stop.py").exists())
        assertFalse(File(userHome, ".jugg/hooks").exists())
        assertTrue(File(userHome, ".claude/settings.json").exists())
    }
}
