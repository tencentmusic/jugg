package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Install channel for Cursor.
 */
object CursorAgentInstaller : IAgentInstaller {
    override val client: InstallClient = InstallClient.CURSOR

    override fun resolvePrimarySkillRoot(userHome: File): File {
        return File(userHome, ".cursor/skills")
    }
}
