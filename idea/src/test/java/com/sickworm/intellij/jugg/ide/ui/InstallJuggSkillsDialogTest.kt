package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.logic.InstallClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class InstallJuggSkillsDialogTest {

    @Test
    fun exportAndInstallSkills_shouldInstallSkillToClientConfigDir() {
        val projectDir = Files.createTempDirectory("jugg-project-manual").toFile()
        val userHome = Files.createTempDirectory("jugg-home-manual").toFile()
        val logger = mock(Logger::class.java)

        InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            selectedClients = setOf(InstallClient.CLAUDE),
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
            selectedClients = emptySet(),
            logger = logger,
            userHome = userHome,
        )
        // no exception expected
    }
}
