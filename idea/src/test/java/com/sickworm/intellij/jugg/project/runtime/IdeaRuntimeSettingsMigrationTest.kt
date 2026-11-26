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
    val settingsFileRule = com.sickworm.intellij.jugg.mock.SettingsFileRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `migration reads only explicitly stored legacy values`() {
        val properties = mock<PropertiesComponent>()
        whenever(properties.isValueSet("jugg.compileOnSave")).thenReturn(true)
        whenever(properties.getValue("jugg.compileOnSave")).thenReturn("true")
        whenever(properties.isValueSet("jugg.isUseProjectKotlinCompiler_v3")).thenReturn(true)
        whenever(properties.getValue("jugg.isUseProjectKotlinCompiler_v3")).thenReturn("false")
        whenever(properties.isValueSet("jugg.isEnableCompatibleDeploymentMode")).thenReturn(true)
        whenever(properties.getValue("jugg.isEnableCompatibleDeploymentMode")).thenReturn("false")
        whenever(properties.isValueSet("jugg.serverExpireTimeMill")).thenReturn(true)
        whenever(properties.getValue("jugg.serverExpireTimeMill")).thenReturn("123")

        listOf("isEnableNativeSandboxDeploy", "isGradleCompileNoDaemon").forEach { key ->
            whenever(properties.isValueSet("jugg.$key")).thenReturn(true)
            whenever(properties.getValue("jugg.$key")).thenReturn("true")
        }
        val values = readLegacyJuggSettings(properties)

        assertEquals(
            setOf(
                "compileOnSave",
                "isUseProjectKotlinCompiler",
                "isEnableCompatibleDeploymentMode",
                "serverExpireTimeMill",
                "isEnableNativeSandboxDeploy",
                "isGradleCompileNoDaemon",
            ),
            values.keys,
        )
        assertTrue(values.getValue("compileOnSave").asBoolean)
        assertTrue(values.getValue("isEnableNativeSandboxDeploy").asBoolean)
        assertTrue(values.getValue("isGradleCompileNoDaemon").asBoolean)
        assertFalse(values.getValue("isUseProjectKotlinCompiler").asBoolean)
        assertFalse(values.getValue("isEnableCompatibleDeploymentMode").asBoolean)
        assertEquals(123L, values.getValue("serverExpireTimeMill").asLong)
    }

    @Test
    fun `migration preserves inherited backend identity in shared settings`() {
        val properties = mock<PropertiesComponent>()
        val rules = "[{\"url\":\"https://backend.example\"}]"
        whenever(properties.isValueSet("jugg.inheritedServerRulesJson")).thenReturn(true)
        whenever(properties.getValue("jugg.inheritedServerRulesJson")).thenReturn(rules)
        assertTrue(JuggSettings.migrateLegacyJuggSettings(properties))
        assertEquals(rules, JuggSettings.snapshot()["inheritedServerRulesJson"]?.asString)
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
        whenever(properties.isValueSet("jugg.isConfirmFallbackWhenTooManyChanges")).thenReturn(true)
        whenever(properties.getValue("jugg.isConfirmFallbackWhenTooManyChanges")).thenReturn("false")
        assertTrue(JuggSettings.migrateLegacyJuggSettings(properties))
        assertFalse(JuggSettings.isConfirmFallbackWhenTooManyChanges)
        verify(properties).setValue(RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY, true, false)
    }

    @Test
    fun `failed migration does not record completion`() {
        val properties = mock<PropertiesComponent>()
        whenever(properties.isValueSet("jugg.compileOnSave")).thenReturn(true)
        whenever(properties.getValue("jugg.compileOnSave")).thenReturn("true")
        JuggGlobalPathManager.settingsFile.writeText("invalid JSON")
        assertFalse(JuggSettings.migrateLegacyJuggSettings(properties))
        verify(properties, never()).setValue(RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY, true, false)
    }
}
