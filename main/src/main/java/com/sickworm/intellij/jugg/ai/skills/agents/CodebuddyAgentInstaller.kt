package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for CodeBuddy.
 */
object CodebuddyAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.CODEBUDDY

    override fun resolvePrimarySkillRoot(userHome: File): File {
        return File(userHome, ".codebuddy/skills")
    }

    override fun resolveHookTargets(userHome: File): List<AgentHookTarget> {
        return listOf(
            AgentHookTarget(
                settingsFile = File(userHome, ".codebuddy/settings.json"),
                style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                startEventName = "UserPromptSubmit",
                stopEventName = "Stop",
                clientArgument = client.cliName,
            ),
            AgentHookTarget(
                settingsFile = File(userHome, ".codebuddy/settings.local.json"),
                style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                startEventName = "UserPromptSubmit",
                stopEventName = "Stop",
                clientArgument = client.cliName,
            ),
        )
    }
}
