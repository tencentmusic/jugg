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
        return listOfNotNull(
            File(userHome, ".gemini-internal"),
            resolveAntigravityHome(userHome),
        )
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
        listOf(File(userHome, ".gemini-internal"))
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
        resolveAntigravityHome(userHome)
            ?.let { configHome ->
                targets += AgentHookTarget(
                    settingsFile = File(configHome, "hooks.json"),
                    style = AgentHookConfigStyle.NAMED_EVENT_HOOKS,
                    startEventName = "PreInvocation",
                    stopEventName = "Stop",
                    clientArgument = "antigravity",
                    editEventName = "PreToolUse",
                    commandEventName = "PreToolUse",
                    editMatcher = "replace_file_content|write_to_file|write_file|edit_file",
                    commandMatcher = "run_command",
                    hookName = "jugg-android-dev-loop",
                )
            }
        return targets
    }

    private fun resolveAntigravityHome(userHome: File): File? {
        return File(userHome, ".gemini/config").takeIf { it.exists() }
    }

    private fun File.isDefaultUserHome(): Boolean {
        return absoluteFile == File(System.getProperty("user.home")).absoluteFile
    }
}
