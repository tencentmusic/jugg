package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.mockito.Mockito.mock

class JuggSkillInstallerTest {

    @Test
    fun install_shouldSucceedForCodexWithoutScript() {
        val projectDir = Files.createTempDirectory("jugg-project").toFile()
        val userHome = Files.createTempDirectory("jugg-home").toFile()
        val logger = mock(Logger::class.java)

        val summary = JuggSkillInstaller.install(
            projectDir = projectDir,
            selectedClients = setOf(InstallClient.CODEX),
            logger = logger,
            userHome = userHome,
        )

        assertTrue(summary.isAllSuccess)
        assertEquals(1, summary.results.size)
        assertEquals("ok", summary.results[0].skillStatus)

        val skillFile = File(userHome, ".codex/skills/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
    }

    @Test
    fun install_shouldReturnEmptyWhenNoClientSelected() {
        val logger = mock(Logger::class.java)
        val result = JuggSkillInstaller.install(
            projectDir = Files.createTempDirectory("jugg-project-empty").toFile(),
            selectedClients = emptySet(),
            logger = logger,
            userHome = Files.createTempDirectory("jugg-home-empty").toFile(),
        )
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun install_codexSkill_shouldRemainStableAfterRepeatedInstall() {
        val projectDir = Files.createTempDirectory("jugg-project-codex-repeat").toFile()
        val userHome = Files.createTempDirectory("jugg-home-codex-repeat").toFile()
        val logger = mock(Logger::class.java)

        repeat(2) {
            JuggSkillInstaller.install(
                projectDir = projectDir,
                selectedClients = setOf(InstallClient.CODEX),
                logger = logger,
                userHome = userHome,
            )
        }

        val skillFile = File(userHome, ".codex/skills/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
    }

    @Test
    fun install_geminiSkill_shouldRemainStableAfterRepeatedInstall() {
        val projectDir = Files.createTempDirectory("jugg-project-gemini-repeat").toFile()
        val userHome = Files.createTempDirectory("jugg-home-gemini-repeat").toFile()
        val logger = mock(Logger::class.java)

        repeat(2) {
            JuggSkillInstaller.install(
                projectDir = projectDir,
                selectedClients = setOf(InstallClient.GEMINI),
                logger = logger,
                userHome = userHome,
            )
        }

        val skillFile = File(userHome, ".gemini/skills/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
    }

    @Test
    fun install_claudeSkill_shouldRemainStableAfterRepeatedInstall() {
        val projectDir = Files.createTempDirectory("jugg-project-claude-repeat").toFile()
        val userHome = Files.createTempDirectory("jugg-home-claude-repeat").toFile()
        val logger = mock(Logger::class.java)

        repeat(2) {
            JuggSkillInstaller.install(
                projectDir = projectDir,
                selectedClients = setOf(InstallClient.CLAUDE),
                logger = logger,
                userHome = userHome,
            )
        }

        val skillFile = File(userHome, ".claude/skills/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
    }

    @Test
    fun install_shouldSucceedForCodebuddy() {
        val userHome = Files.createTempDirectory("jugg-home-codebuddy").toFile()
        val logger = mock(Logger::class.java)
        JuggSkillInstaller.install(
            projectDir = Files.createTempDirectory("jugg-project-codebuddy").toFile(),
            selectedClients = setOf(InstallClient.CODEBUDDY),
            logger = logger,
            userHome = userHome,
        )
        assertTrue(File(userHome, ".codebuddy/skills/jugg-android-dev-loop/SKILL.md").exists())
    }

    @Test
    fun install_claude_shouldAutoInstallToInternalDirWhenExists() {
        val userHome = Files.createTempDirectory("jugg-home-claude-auto-internal").toFile()
        File(userHome, ".claude-internal").mkdirs()
        val logger = mock(Logger::class.java)
        JuggSkillInstaller.install(
            projectDir = Files.createTempDirectory("jugg-project-claude-auto").toFile(),
            selectedClients = setOf(InstallClient.CLAUDE),
            logger = logger,
            userHome = userHome,
        )
        assertTrue(File(userHome, ".claude/skills/jugg-android-dev-loop/SKILL.md").exists())
        assertTrue(File(userHome, ".claude-internal/skills/jugg-android-dev-loop/SKILL.md").exists())
    }

    @Test
    fun install_claude_shouldNotInstallToInternalDirWhenAbsent() {
        val userHome = Files.createTempDirectory("jugg-home-claude-no-internal").toFile()
        val logger = mock(Logger::class.java)
        JuggSkillInstaller.install(
            projectDir = Files.createTempDirectory("jugg-project-claude-no-internal").toFile(),
            selectedClients = setOf(InstallClient.CLAUDE),
            logger = logger,
            userHome = userHome,
        )
        assertTrue(File(userHome, ".claude/skills/jugg-android-dev-loop/SKILL.md").exists())
        assertFalse(File(userHome, ".claude-internal").exists())
    }

    @Test
    fun install_codex_shouldAutoInstallToInternalDirWhenExists() {
        val userHome = Files.createTempDirectory("jugg-home-codex-auto-internal").toFile()
        File(userHome, ".codex-internal").mkdirs()
        val logger = mock(Logger::class.java)
        JuggSkillInstaller.install(
            projectDir = Files.createTempDirectory("jugg-project-codex-auto").toFile(),
            selectedClients = setOf(InstallClient.CODEX),
            logger = logger,
            userHome = userHome,
        )
        assertTrue(File(userHome, ".codex/skills/jugg-android-dev-loop/SKILL.md").exists())
        assertTrue(File(userHome, ".codex-internal/skills/jugg-android-dev-loop/SKILL.md").exists())
    }

    @Test
    fun install_gemini_shouldAutoInstallToInternalDirWhenExists() {
        val userHome = Files.createTempDirectory("jugg-home-gemini-auto-internal").toFile()
        File(userHome, ".gemini-internal").mkdirs()
        val logger = mock(Logger::class.java)
        JuggSkillInstaller.install(
            projectDir = Files.createTempDirectory("jugg-project-gemini-auto").toFile(),
            selectedClients = setOf(InstallClient.GEMINI),
            logger = logger,
            userHome = userHome,
        )
        assertTrue(File(userHome, ".gemini/skills/jugg-android-dev-loop/SKILL.md").exists())
        assertTrue(File(userHome, ".gemini-internal/skills/jugg-android-dev-loop/SKILL.md").exists())
    }
}

