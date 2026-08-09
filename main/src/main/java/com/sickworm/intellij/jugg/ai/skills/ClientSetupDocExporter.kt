package com.sickworm.intellij.jugg.ai.skills

import com.sickworm.intellij.jugg.project.runtime.withGlobalResourceLock
import java.io.File
import java.io.FileNotFoundException

/**
 * Resolves client setup guide from bundled skills under user home.
 */
object ClientSetupDocExporter {

    private const val SKILL_NAME = "jugg-android-dev-loop"
    private const val SETUP_DOC_RELATIVE_PATH = "install/agent_setup.md"

    /**
     * Ensures bundled skills are available and returns setup guide under ~/.jugg/skills.
     */
    fun export(
        @Suppress("UNUSED_PARAMETER") projectDir: File,
        userHome: File = File(System.getProperty("user.home")),
    ): File {
        val globalRootDir = File(userHome, ".jugg")
        return withGlobalResourceLock("Export client setup guide", globalRootDir) {
            val bundledSkillsDir = JuggSkillInstaller.ensureBundledSkillsHome(userHome)
            val setupDocFile = File(bundledSkillsDir, SETUP_DOC_RELATIVE_PATH)
            if (!File(bundledSkillsDir, SKILL_NAME).isDirectory) {
                throw FileNotFoundException("Resource not found: $SKILL_NAME")
            }
            if (!setupDocFile.isFile) {
                throw FileNotFoundException("Resource not found: $SETUP_DOC_RELATIVE_PATH")
            }
            setupDocFile
        }
    }
}
