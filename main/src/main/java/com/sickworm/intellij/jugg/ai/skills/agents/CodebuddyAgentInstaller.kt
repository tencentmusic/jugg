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
}
