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
}
