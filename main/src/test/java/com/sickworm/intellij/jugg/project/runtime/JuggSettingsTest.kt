package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Verifies automatic settings loading, migration refresh, and global-root isolation. */
class JuggSettingsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `settings automatically follow the active global root`() {
        val oldRootDir = JuggGlobalPathManager.rootDir
        val firstRoot = temporaryFolder.newFolder("first")
        val secondRoot = temporaryFolder.newFolder("second")
        JuggGlobalPathManager.settingsFile(firstRoot).writeText("""{"compileOnSave":true}""")
        JuggGlobalPathManager.settingsFile(secondRoot).writeText("""{"compileOnSave":false}""")

        try {
            JuggGlobalPathManager.rootDir = firstRoot
            assertTrue(JuggSettings.compileOnSave)

            JuggSettings.deployOnSave = true
            assertTrue(JsonParser.parseString(JuggGlobalPathManager.settingsFile.readText()).asJsonObject.get("deployOnSave").asBoolean)

            JuggGlobalPathManager.rootDir = secondRoot
            assertFalse(JuggSettings.compileOnSave)
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }

    @Test
    fun `migration invalidates already loaded settings`() {
        val oldRootDir = JuggGlobalPathManager.rootDir
        val rootDir = temporaryFolder.newFolder("migration")
        JuggGlobalPathManager.settingsFile(rootDir).writeText("""{"compileOnSave":false}""")

        try {
            JuggGlobalPathManager.rootDir = rootDir
            assertFalse(JuggSettings.compileOnSave)

            assertTrue(JuggSettings.migrate(mapOf("deployOnSave" to JsonPrimitive(true))))

            assertTrue(JuggSettings.deployOnSave)
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }

    @Test
    fun `reload reads settings updated by another runtime`() {
        val oldRootDir = JuggGlobalPathManager.rootDir
        val rootDir = temporaryFolder.newFolder("runtime_switch")
        val settingsFile = JuggGlobalPathManager.settingsFile(rootDir)
        settingsFile.writeText("""{"isEnableCompatibleDeploymentMode":false}""")

        try {
            JuggGlobalPathManager.rootDir = rootDir
            assertFalse(JuggSettings.isEnableCompatibleDeploymentMode)

            settingsFile.writeText("""{"isEnableCompatibleDeploymentMode":true}""")
            JuggSettings.reload()

            assertTrue(JuggSettings.isEnableCompatibleDeploymentMode)
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }

    @Test
    fun `process backup classpath override is not persisted`() {
        val oldRootDir = JuggGlobalPathManager.rootDir
        val rootDir = temporaryFolder.newFolder("process_override")

        try {
            JuggGlobalPathManager.rootDir = rootDir
            JuggSettings.isForceEnableBackupClasspath = true

            assertTrue(JuggSettings.finalIsEnableBackupClasspath)
            assertFalse(JuggGlobalPathManager.settingsFile.exists())
        } finally {
            JuggSettings.isForceEnableBackupClasspath = false
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }

    @Test
    fun `remote command history is isolated deduplicated and limited`() {
        val oldRootDir = JuggGlobalPathManager.rootDir
        val rootDir = temporaryFolder.newFolder("remote_history")

        try {
            JuggGlobalPathManager.rootDir = rootDir
            repeat(11) { JuggSettings.recordRemoteCommand("target-a", "command-$it") }
            JuggSettings.recordRemoteCommand("target-a", "command-5")
            JuggSettings.recordRemoteCommand("target-b", "other-command")

            assertEquals(
                listOf("command-5", "command-10", "command-9", "command-8", "command-7",
                    "command-6", "command-4", "command-3", "command-2", "command-1"),
                JuggSettings.getRemoteCommandHistory("target-a"),
            )
            assertEquals(listOf("other-command"), JuggSettings.getRemoteCommandHistory("target-b"))
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }

    @Test
    fun `remote command history ignores blank commands and corrupted data`() {
        val oldRootDir = JuggGlobalPathManager.rootDir
        val rootDir = temporaryFolder.newFolder("remote_history_corrupt")
        JuggGlobalPathManager.settingsFile(rootDir).writeText("""{"remoteCommandHistoryJson":"not-json"}""")

        try {
            JuggGlobalPathManager.rootDir = rootDir
            assertEquals(emptyList<String>(), JuggSettings.getRemoteCommandHistory("target"))

            JuggSettings.recordRemoteCommand("target", "  ")

            assertEquals(emptyList<String>(), JuggSettings.getRemoteCommandHistory("target"))
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }
}
