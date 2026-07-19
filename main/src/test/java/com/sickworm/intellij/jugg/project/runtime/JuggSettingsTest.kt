package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
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
}
