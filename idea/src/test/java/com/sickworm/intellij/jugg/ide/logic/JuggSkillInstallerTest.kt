package com.sickworm.intellij.jugg.ide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JuggSkillInstallerTest {

    @Test
    fun buildCommand_shouldContainClientAndMcpFlags() {
        val command = JuggSkillInstaller.buildCommand("/repo/docs/skills/install/install_mcp_and_skill.sh", InstallClient.CODEX)

        assertEquals("bash", command[0])
        assertEquals("/repo/docs/skills/install/install_mcp_and_skill.sh", command[1])
        assertTrue(command.contains("--with-mcp"))
        assertTrue(command.contains("--mcp-client"))
        assertTrue(command.contains("codex"))
    }

    @Test
    fun summarize_shouldReportSuccessWhenSkillAndMcpAreOk() {
        val lines = listOf(
            "SKILL agent=codex status=ok file=/Users/test/.codex/skills/jugg-android-dev-loop",
            "MCP agent=codex status=ok file=/Users/test/.codex/config.toml",
        )

        val summary = JuggSkillInstaller.summarize(lines, setOf(InstallClient.CODEX))

        assertTrue(summary.isAllSuccess)
        assertEquals(1, summary.results.size)
        assertEquals("ok", summary.results[0].skillStatus)
        assertEquals("ok", summary.results[0].mcpStatus)
    }

    @Test
    fun summarize_shouldReportFailureReasonWhenMcpFailed() {
        val lines = listOf(
            "SKILL agent=claude status=ok file=/Users/test/.claude/skills/jugg-android-dev-loop",
            "MCP agent=claude status=fail file=/Users/test/.claude.json reason=command_not_found",
        )

        val summary = JuggSkillInstaller.summarize(lines, setOf(InstallClient.CLAUDE))

        assertFalse(summary.isAllSuccess)
        assertEquals("fail", summary.results[0].mcpStatus)
        assertTrue(summary.results[0].reasons.any { it.contains("command_not_found") })
    }
}
