package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for Gemini.
 */
object GeminiAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.GEMINI

    override fun resolvePrimarySkillRoot(userHome: File): File {
        return File(resolveGeminiHome(userHome), "skills")
    }

    private fun resolveGeminiHome(userHome: File): File {
        val envHome = System.getenv("GEMINI_HOME").takeIf { userHome.isDefaultUserHome() }
        return if (!envHome.isNullOrBlank()) File(envHome) else File(userHome, ".gemini")
    }

    override fun resolveInternalSkillHomes(userHome: File): List<File> {
        return listOf(File(userHome, ".gemini-internal"))
    }

    override fun resolveHookTargets(userHome: File): List<AgentHookTarget> {
        // https://geminicli.com/docs/hooks/
        val targets = mutableListOf(
            AgentHookTarget(
                settingsFile = File(resolveGeminiHome(userHome), "settings.json"),
                style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                startEventName = "BeforeAgent",
                stopEventName = "AfterAgent",
                clientArgument = client.cliName,
                editEventName = "AfterTool",
                commandEventName = "BeforeTool",
                editMatcher = "write_file|replace",
                commandMatcher = "run_shell_command",
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
                    editMatcher = "write_file|replace",
                    commandMatcher = "run_shell_command",
                )
            }
        return targets
    }

    private fun File.isDefaultUserHome(): Boolean {
        return absoluteFile == File(System.getProperty("user.home")).absoluteFile
    }
}
