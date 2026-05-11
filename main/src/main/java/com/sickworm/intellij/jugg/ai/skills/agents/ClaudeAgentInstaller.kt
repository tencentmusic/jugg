package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for Claude.
 */
object ClaudeAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.CLAUDE

    override fun resolvePrimarySkillRoot(userHome: File): File {
        val dotClaude = File(userHome, ".claude")
        val configClaude = File(userHome, ".config/claude")
        val claudeHome = when {
            dotClaude.exists() -> dotClaude
            configClaude.exists() -> configClaude
            else -> dotClaude
        }
        return File(claudeHome, "skills")
    }

    override fun resolveInternalSkillHomes(userHome: File): List<File> {
        return listOf(File(userHome, ".claude-internal"))
    }

    override fun resolveHookTargets(userHome: File): List<AgentHookTarget> {
        // https://code.claude.com/docs/en/hooks
        val primary = AgentHookTarget(
            settingsFile = File(userHome, ".claude/settings.json"),
            style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
            startEventName = "UserPromptSubmit",
            stopEventName = "Stop",
            clientArgument = client.cliName,
            editEventName = "PostToolUse",
            commandEventName = "PreToolUse",
            editMatcher = "Edit|Write|MultiEdit|apply_patch",
            commandMatcher = "Bash",
        )
        val targets = mutableListOf(primary)
        resolveInternalSkillHomes(userHome)
            .filter { it.exists() }
            .forEach { internalHome ->
                targets += AgentHookTarget(
                    settingsFile = File(internalHome, "settings.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = client.cliName,
                    editEventName = "PostToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "Edit|Write|MultiEdit|apply_patch",
                    commandMatcher = "Bash",
                )
            }
        return targets
    }
}
