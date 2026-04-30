package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for Codex.
 */
object CodexAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.CODEX

    override fun resolvePrimarySkillRoot(userHome: File): File {
        val envHome = System.getenv("CODEX_HOME")
        val codexHome = if (!envHome.isNullOrBlank()) File(envHome) else File(userHome, ".codex")
        return File(codexHome, "skills")
    }

    override fun resolveInternalSkillHomes(userHome: File): List<File> {
        return listOf(File(userHome, ".codex-internal"))
    }

    override fun resolveHookTargets(userHome: File): List<AgentHookTarget> {
        val targets = mutableListOf(
            AgentHookTarget(
                settingsFile = File(userHome, ".codex/hooks.json"),
                style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                startEventName = "UserPromptSubmit",
                stopEventName = "Stop",
                clientArgument = client.cliName,
            ),
        )
        resolveInternalSkillHomes(userHome)
            .filter { it.exists() }
            .forEach { internalHome ->
                targets += AgentHookTarget(
                    settingsFile = File(internalHome, "hooks.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "UserPromptSubmit",
                    stopEventName = "Stop",
                    clientArgument = client.cliName,
                )
            }
        return targets
    }
}
