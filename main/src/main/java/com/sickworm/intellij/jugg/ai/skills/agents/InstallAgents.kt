package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient

/**
 * Central dispatcher for install agents.
 * Installers should only depend on this object when resolving channels.
 */
object InstallAgents {
    private val agentInstallers: Map<InstallClient, IAgentInstaller> = listOf(
        CodexAgentInstaller,
        ClaudeAgentInstaller,
        GeminiAgentInstaller,
        CodebuddyAgentInstaller,
        CursorAgentInstaller,
    ).associateBy { it.client }

    fun resolveAgentInstaller(client: InstallClient): IAgentInstaller {
        return agentInstallers[client]
            ?: throw IllegalArgumentException("unsupported_skill_install_client_${client.name}")
    }

    fun allInstallers(): List<IAgentInstaller> {
        return agentInstallers.values.toList()
    }
}
