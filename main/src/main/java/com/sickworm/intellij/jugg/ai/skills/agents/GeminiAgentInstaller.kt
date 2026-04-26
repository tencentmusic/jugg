package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for Gemini.
 */
object GeminiAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.GEMINI

    override fun resolvePrimarySkillRoot(userHome: File): File {
        val envHome = System.getenv("GEMINI_HOME")
        val geminiHome = if (!envHome.isNullOrBlank()) File(envHome) else File(userHome, ".gemini")
        return File(geminiHome, "skills")
    }

    override fun resolveInternalSkillHomes(userHome: File): List<File> {
        return listOf(File(userHome, ".gemini-internal"))
    }

    override fun resolveHookTargets(userHome: File): List<AgentHookTarget> {
        return listOf(
            AgentHookTarget(
                settingsFile = File(userHome, ".gemini/settings.json"),
                style = AgentHookConfigStyle.NESTED_EVENT_HOOKS,
                startEventName = "SessionStart",
                stopEventName = "SessionEnd",
            ),
        )
    }
}
