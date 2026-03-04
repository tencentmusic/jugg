package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonParser
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

    @Test
    fun install_codexMcpConfig_shouldDeduplicateExistingServerSectionVariants() {
        val projectDir = Files.createTempDirectory("jugg-project-dup").toFile()
        val userHome = Files.createTempDirectory("jugg-home-dup").toFile()
        val logger = mock(Logger::class.java)
        val config = File(userHome, ".codex/config.toml")
        config.parentFile?.mkdirs()
        config.writeText(
            """
            [mcp_servers.'jugg-mcp']
            url = "http://old-endpoint"

            [mcp_servers]
            "jugg-mcp" = { url = "http://old-endpoint-inline" }
            """.trimIndent() + "\n",
        )

        JuggSkillInstaller.install(
            projectDir = projectDir,
            selectedClients = setOf(InstallClient.CODEX),
            logger = logger,
            userHome = userHome,
        )

        val content = config.readText()
        assertEquals(1, countMcpServerSections(content))
        assertEquals(0, countMcpServerInlineKeys(content))
        assertTrue(content.contains("[mcp_servers.\"jugg-mcp\"]"))
        assertTrue(content.contains("url = \"http://localhost:12320/jugg-mcp\""))
    }

    @Test
    fun install_codexMcpConfig_shouldRemainStableAfterRepeatedInstall() {
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

        val content = File(userHome, ".codex/config.toml").readText()
        assertEquals(1, countMcpServerSections(content))
        assertEquals(0, countMcpServerInlineKeys(content))
    }

    @Test
    fun install_geminiMcpConfig_shouldRemainStableAfterRepeatedInstall() {
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

        val settingsFile = File(userHome, ".gemini/settings.json")
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val mcpServers = root.getAsJsonObject("mcpServers")
        val names = mcpServers.keySet().toList()
        assertEquals(1, names.size)
        assertEquals("jugg-mcp", names[0])
        assertEquals("http://localhost:12320/jugg-mcp", mcpServers.getAsJsonObject("jugg-mcp").get("httpUrl").asString)
    }

    @Test
    fun install_claudeMcpConfig_shouldRemainStableAfterRepeatedInstall() {
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

        val settingsFile = File(userHome, ".claude.json")
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val mcpServers = root.getAsJsonObject("mcpServers")
        val names = mcpServers.keySet().toList()
        assertEquals(1, names.size)
        assertEquals("jugg-mcp", names[0])
        assertEquals("http://localhost:12320/jugg-mcp", mcpServers.getAsJsonObject("jugg-mcp").get("httpUrl").asString)
    }

    private fun countMcpServerSections(content: String): Int {
        return Regex("""(?m)^\[\s*mcp_servers\s*\.\s*(?:"jugg-mcp"|'jugg-mcp'|jugg-mcp)\s*]\s*$""")
            .findAll(content)
            .count()
    }

    private fun countMcpServerInlineKeys(content: String): Int {
        return Regex("""(?m)^\s*(?:"jugg-mcp"|'jugg-mcp'|jugg-mcp)\s*=.*$""")
            .findAll(content)
            .count()
    }
}
