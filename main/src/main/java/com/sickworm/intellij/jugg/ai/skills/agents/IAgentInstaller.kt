package com.sickworm.intellij.jugg.ai.skills.agents

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import java.io.File

/**
 * Defines install targets for one client channel.
 * One installer can provide both skill and hook locations.
 */
interface IAgentInstaller {
    val client: InstallClient

    fun resolvePrimarySkillRoot(userHome: File): File

    fun resolveInternalSkillHomes(userHome: File): List<File> = emptyList()

    fun resolveHookSettingsFiles(userHome: File): List<File> = emptyList()
}
