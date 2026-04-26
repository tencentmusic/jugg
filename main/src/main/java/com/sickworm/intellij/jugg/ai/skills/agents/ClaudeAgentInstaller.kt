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

    override fun resolveHookSettingsFiles(userHome: File): List<File> {
        return listOf(
            File(userHome, ".claude/settings.json"),
            File(userHome, ".claude-internal/settings.json"),
        )
    }
}
