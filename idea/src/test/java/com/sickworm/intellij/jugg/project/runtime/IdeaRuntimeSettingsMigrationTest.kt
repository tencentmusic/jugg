package com.sickworm.intellij.jugg.project.runtime

import com.intellij.ide.util.PropertiesComponent
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/** Verifies legacy IDEA property conversion before JSON migration. */
class IdeaRuntimeSettingsMigrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `migration reads only explicitly stored legacy values`() {
        val properties = mock<PropertiesComponent>()
        whenever(properties.isValueSet("jugg.compileOnSave")).thenReturn(true)
        whenever(properties.getValue("jugg.compileOnSave")).thenReturn("true")
        whenever(properties.isValueSet("jugg.isUseProjectKotlinCompiler_v3")).thenReturn(true)
        whenever(properties.getValue("jugg.isUseProjectKotlinCompiler_v3")).thenReturn("false")
        whenever(properties.isValueSet("jugg.serverExpireTimeMill")).thenReturn(true)
        whenever(properties.getValue("jugg.serverExpireTimeMill")).thenReturn("123")

        val values = readLegacyJuggSettings(properties)

        assertEquals(setOf("compileOnSave", "isUseProjectKotlinCompiler", "serverExpireTimeMill"), values.keys)
        assertTrue(values.getValue("compileOnSave").asBoolean)
        assertFalse(values.getValue("isUseProjectKotlinCompiler").asBoolean)
        assertEquals(123L, values.getValue("serverExpireTimeMill").asLong)
    }

    @Test
    fun `migration without stored properties returns empty values`() {
        assertTrue(readLegacyJuggSettings(mock()).isEmpty())
    }

    @Test
    fun `successful migration records completion`() {
        val properties = mock<PropertiesComponent>()
        whenever(properties.isValueSet("jugg.compileOnSave")).thenReturn(true)
        whenever(properties.getValue("jugg.compileOnSave")).thenReturn("true")
        val oldRootDir = JuggGlobalPathManager.rootDir

        try {
            JuggGlobalPathManager.rootDir = temporaryFolder.newFolder("success")

            assertTrue(JuggSettings.migrateLegacyJuggSettings(properties))

            verify(properties).setValue(RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY, true, false)
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }

    @Test
    fun `failed migration does not record completion`() {
        val properties = mock<PropertiesComponent>()
        whenever(properties.isValueSet("jugg.compileOnSave")).thenReturn(true)
        whenever(properties.getValue("jugg.compileOnSave")).thenReturn("true")
        val oldRootDir = JuggGlobalPathManager.rootDir
        val blockedParent = temporaryFolder.newFile("blocked_parent")

        try {
            JuggGlobalPathManager.rootDir = File(blockedParent, ".jugg")

            assertFalse(JuggSettings.migrateLegacyJuggSettings(properties))

            verify(properties, never()).setValue(RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY, true, false)
        } finally {
            JuggGlobalPathManager.rootDir = oldRootDir
        }
    }
}
