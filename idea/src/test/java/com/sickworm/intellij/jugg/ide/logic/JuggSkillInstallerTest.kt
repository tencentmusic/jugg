package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
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
        assertEquals("ok", summary.results[0].mcpStatus)

        val skillFile = File(userHome, ".codex/skills/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
        val config = File(userHome, ".codex/config.toml")
        assertTrue(config.exists())
        assertTrue(config.readText().contains("[mcp_servers.\"jugg-mcp\"]"))
        assertTrue(config.readText().contains("url = \"http://localhost:12320/jugg-mcp\""))
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
}
