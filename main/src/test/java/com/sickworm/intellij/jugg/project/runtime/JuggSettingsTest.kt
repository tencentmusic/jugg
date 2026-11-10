package com.sickworm.intellij.jugg.project.runtime

import com.sickworm.intellij.jugg.mock.SettingsFileRule
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Verifies automatic settings loading, migration refresh, and persisted updates. */
class JuggSettingsTest {
    @get:Rule
    val settingsFileRule = SettingsFileRule()

    @Test
    fun `settings refresh after another runtime replaces the settings file`() {
        JuggGlobalPathManager.settingsFile.writeText("""{"compileOnSave":true}""")
        assertTrue(JuggSettings.compileOnSave)
        JuggSettings.deployOnSave = true
        assertTrue(JsonParser.parseString(JuggGlobalPathManager.settingsFile.readText()).asJsonObject.get("deployOnSave").asBoolean)
        JuggGlobalPathManager.settingsFile.writeText("""{"compileOnSave":false}""")
        assertFalse(JuggSettings.compileOnSave)
    }

    @Test
    fun `migration invalidates already loaded settings`() {
        JuggGlobalPathManager.settingsFile.writeText("""{"compileOnSave":false}""")

        assertFalse(JuggSettings.compileOnSave)

        assertTrue(JuggSettings.migrate(mapOf("deployOnSave" to JsonPrimitive(true))))

        assertTrue(JuggSettings.deployOnSave)
    }

    @Test
    fun `reload reads settings updated by another runtime`() {
        val settingsFile = JuggGlobalPathManager.settingsFile
        settingsFile.writeText("""{"compileOnSave":false}""")

        assertFalse(JuggSettings.compileOnSave)

        settingsFile.writeText("""{"compileOnSave":true}""")
        JuggSettings.reload()

        assertTrue(JuggSettings.compileOnSave)
    }

    @Test
    fun `process backup classpath override is not persisted`() {
        try {
            JuggSettings.isForceEnableBackupClasspath = true

            assertTrue(JuggSettings.finalIsEnableBackupClasspath)
            assertFalse(JuggGlobalPathManager.settingsFile.exists())
        } finally {
            JuggSettings.isForceEnableBackupClasspath = false
        }
    }

    @Test
    fun `remote command history is isolated deduplicated and limited`() {
        repeat(11) { JuggSettings.recordRemoteCommand("target-a", "command-$it") }
        JuggSettings.recordRemoteCommand("target-a", "command-5")
        JuggSettings.recordRemoteCommand("target-b", "other-command")

        assertEquals(
            listOf("command-5", "command-10", "command-9", "command-8", "command-7",
                "command-6", "command-4", "command-3", "command-2", "command-1"),
            JuggSettings.getRemoteCommandHistory("target-a"),
        )
        assertEquals(listOf("other-command"), JuggSettings.getRemoteCommandHistory("target-b"))
    }

    @Test
    fun `remote command history ignores blank commands and corrupted data`() {
        JuggGlobalPathManager.settingsFile.writeText("""{"remoteCommandHistoryJson":"not-json"}""")

        assertEquals(emptyList<String>(), JuggSettings.getRemoteCommandHistory("target"))

        JuggSettings.recordRemoteCommand("target", "  ")

        assertEquals(emptyList<String>(), JuggSettings.getRemoteCommandHistory("target"))
    }
}
