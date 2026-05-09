package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for Gemini.
 */
object GeminiAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.GEMINI

    override fun resolvePrimarySkillRoot(userHome: File): File {
        val envHome = System.getenv("GEMINI_HOME").takeIf { userHome.isDefaultUserHome() }
        val geminiHome = if (!envHome.isNullOrBlank()) File(envHome) else File(userHome, ".gemini")
        return File(geminiHome, "skills")
    }

    override fun resolveInternalSkillHomes(userHome: File): List<File> {
        return listOf(File(userHome, ".gemini-internal"))
    }

    override fun resolveHookTargets(userHome: File): List<AgentHookTarget> {
        // https://geminicli.com/docs/hooks/
        val targets = mutableListOf(
            AgentHookTarget(
                settingsFile = File(userHome, ".gemini/settings.json"),
                style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                startEventName = "BeforeAgent",
                stopEventName = "AfterAgent",
                clientArgument = client.cliName,
                editEventName = "AfterTool",
                commandEventName = "BeforeTool",
                editMatcher = "*",
                commandMatcher = "*",
            ),
        )
        resolveInternalSkillHomes(userHome)
            .filter { it.exists() }
            .forEach { internalHome ->
                targets += AgentHookTarget(
                    settingsFile = File(internalHome, "hooks.json"),
                    style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                    startEventName = "BeforeAgent",
                    stopEventName = "AfterAgent",
                    clientArgument = client.cliName,
                    editEventName = "AfterTool",
                    commandEventName = "BeforeTool",
                    editMatcher = "*",
                    commandMatcher = "*",
                )
            }
        return targets
    }

    private fun File.isDefaultUserHome(): Boolean {
        return absoluteFile == File(System.getProperty("user.home")).absoluteFile
    }
}
