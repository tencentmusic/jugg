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
        File(userHome, ".claude-internal").mkdirs()
        File(userHome, ".tme-claude").mkdirs()
        val targets = ClaudeAgentInstaller.resolveHookTargets(userHome)

        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".claude/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "claude",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write",
                    commandMatcher = "Bash",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".claude-internal/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "claude",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write",
                    commandMatcher = "Bash",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".tme-claude/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "claude",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write",
                    commandMatcher = "Bash",
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
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "codebuddy",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write",
                    commandMatcher = "Bash",
                    stopMatcher = "",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".codebuddy/settings.local.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "codebuddy",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write",
                    commandMatcher = "Bash",
                    stopMatcher = "",
                ),
            ),
            CodebuddyAgentInstaller.resolveHookTargets(userHome),
        )
        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".cursor/hooks.json"),
                    style = AgentHookConfigStyle.FLAT_EVENT_COMMANDS,
                    startEventName = "beforeSubmitPrompt",
                    stopEventName = "stop",
                    clientArgument = "cursor",
                    editEventName = "afterFileEdit",
                    commandEventName = "beforeShellExecution",
                    editMatcher = "Write",
                    commandMatcher = "*",
                    configVersion = 1,
                ),
            ),
            CursorAgentInstaller.resolveHookTargets(userHome),
        )
    }

    @Test
    fun codexAndGeminiInstallers_shouldExposeHookTargetsWithOwnEventSemantics() {
        val userHome = Files.createTempDirectory("jugg-home-agent-hooks-others").toFile()
        File(userHome, ".codex-internal").mkdirs()
        File(userHome, ".gemini-internal").mkdirs()

        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".codex/hooks.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "codex",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write|apply_patch",
                    commandMatcher = "Bash",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".codex-internal/hooks.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = "codex",
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write|apply_patch",
                    commandMatcher = "Bash",
                ),
            ),
            CodexAgentInstaller.resolveHookTargets(userHome),
        )
        assertEquals(
            listOf(
                AgentHookTarget(
                    settingsFile = File(userHome, ".gemini/settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "BeforeAgent",
                    stopEventName = "AfterAgent",
                    clientArgument = "gemini",
                    editEventName = "AfterTool",
                    commandEventName = "BeforeTool",
                    editMatcher = "write_file|replace",
                    commandMatcher = "run_shell_command",
                ),
                AgentHookTarget(
                    settingsFile = File(userHome, ".gemini-internal/hooks.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "BeforeAgent",
                    stopEventName = "AfterAgent",
                    clientArgument = "gemini",
                    editEventName = "AfterTool",
                    commandEventName = "BeforeTool",
                    editMatcher = "write_file|replace",
                    commandMatcher = "run_shell_command",
                ),
            ),
            GeminiAgentInstaller.resolveHookTargets(userHome),
        )
    }

    @Test
    fun codexAgentInstaller_shouldResolvePrimaryHookTargetFromCodexHome() {
        val userHome = File(System.getProperty("user.home"))
        val codexHome = System.getenv("CODEX_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?: File(userHome, ".codex")

        val target = CodexAgentInstaller.resolveHookTargets(userHome).first()

        assertEquals(File(codexHome, "hooks.json"), target.settingsFile)
    }

    @Test
    fun geminiAgentInstaller_shouldResolvePrimaryHookTargetFromGeminiHome() {
        val userHome = File(System.getProperty("user.home"))
        val geminiHome = System.getenv("GEMINI_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?: File(userHome, ".gemini")

        val target = GeminiAgentInstaller.resolveHookTargets(userHome).first()

        assertEquals(File(geminiHome, "settings.json"), target.settingsFile)
    }

    @Test
    fun codexAgentInstaller_shouldExposePermissionRuleTargetsForPrimaryAndInternalHomes() {
        val userHome = Files.createTempDirectory("jugg-home-codex-rules").toFile()
        File(userHome, ".codex-internal").mkdirs()

        val targets = CodexAgentInstaller.resolvePermissionRuleTargets(userHome, "jugg-android-dev-loop")

        assertEquals(
            listOf(
                AgentPermissionRuleTarget(
                    rulesFile = File(userHome, ".codex/rules/default.rules"),
                    prefixPattern = listOf(
                        "python3",
                        File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath,
                    ),
                ),
                AgentPermissionRuleTarget(
                    rulesFile = File(userHome, ".codex-internal/rules/default.rules"),
                    prefixPattern = listOf(
                        "python3",
                        File(userHome, ".codex-internal/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath,
                    ),
                ),
            ),
            targets,
        )
    }
}
