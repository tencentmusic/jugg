package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallAgentsTest {

    @Test
    fun resolveAgentInstaller_shouldReturnDedicatedInstallerPerClient() {
        assertEquals(CodexAgentInstaller::class, InstallAgents.resolveAgentInstaller(InstallClient.CODEX)::class)
        assertEquals(ClaudeAgentInstaller::class, InstallAgents.resolveAgentInstaller(InstallClient.CLAUDE)::class)
        assertEquals(GeminiAgentInstaller::class, InstallAgents.resolveAgentInstaller(InstallClient.GEMINI)::class)
        assertEquals(CodebuddyAgentInstaller::class, InstallAgents.resolveAgentInstaller(InstallClient.CODEBUDDY)::class)
        assertEquals(CursorAgentInstaller::class, InstallAgents.resolveAgentInstaller(InstallClient.CURSOR)::class)
    }

    @Test
    fun allInstallers_shouldExposeSingleInstallerPerClient() {
        val installers = InstallAgents.allInstallers()
        assertEquals(InstallClient.values().size, installers.size)
    }

    @Test
    fun claudeAgentInstaller_shouldPreferConfigClaudeWhenDotClaudeMissing() {
        val userHome = Files.createTempDirectory("jugg-home-claude-agent-config").toFile()
        File(userHome, ".config/claude").mkdirs()

        val root = ClaudeAgentInstaller.resolvePrimarySkillRoot(userHome)
        assertEquals(File(userHome, ".config/claude/skills"), root)
    }

    @Test
    fun claudeAgentInstaller_shouldReturnTwoHookSettingsTargets() {
        val userHome = Files.createTempDirectory("jugg-home-claude-hook-agent").toFile()
        val targets = ClaudeAgentInstaller.resolveHookTargets(userHome)

        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".claude/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "SessionStart",
                    stopEventName = "Stop",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".claude-internal/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "SessionStart",
                    stopEventName = "Stop",
                ),
            ),
            targets,
        )
    }

    @Test
    fun codebuddyAndCursorInstallers_shouldExposeExpectedHookTargets() {
        val userHome = Files.createTempDirectory("jugg-home-agent-internals").toFile()
        assertTrue(CodebuddyAgentInstaller.resolveInternalSkillHomes(userHome).isEmpty())
        assertTrue(CursorAgentInstaller.resolveInternalSkillHomes(userHome).isEmpty())
        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".codebuddy/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "SessionStart",
                    stopEventName = "Stop",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".codebuddy/settings.local.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "SessionStart",
                    stopEventName = "Stop",
                ),
            ),
            CodebuddyAgentInstaller.resolveHookTargets(userHome),
        )
        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".cursor/hooks.json"),
                    style = AgentHookConfigStyle.FLAT_EVENT_COMMANDS,
                    startEventName = "sessionStart",
                    stopEventName = "sessionEnd",
                ),
            ),
            CursorAgentInstaller.resolveHookTargets(userHome),
        )
    }

    @Test
    fun codexAndGeminiInstallers_shouldExposeHookTargetsWithOwnEventSemantics() {
        val userHome = Files.createTempDirectory("jugg-home-agent-hooks-others").toFile()

        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".codex/hooks.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "SessionStart",
                    stopEventName = "Stop",
                ),
            ),
            CodexAgentInstaller.resolveHookTargets(userHome),
        )
        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".gemini/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "SessionStart",
                    stopEventName = "SessionEnd",
                ),
            ),
            GeminiAgentInstaller.resolveHookTargets(userHome),
        )
    }
}
