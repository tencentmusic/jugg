package com.sickworm.intellij.jugg.ide.ui

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.DummyPropertiesComponent
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.server.protocols.ProjectCustomConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.mockito.Mockito

class ProjectDefaultSettingsApplierTest {
    private val globalProperties by lazy { PropertiesComponent.getInstance() }
    private val savedValues = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        TestGlobal.init()
        listOf("compileOnSave", "deployOnSave", "isEnableDirectOverlayDeploy",
            "isUseProjectKotlinCompiler_v3", "serverExpireTimeMill", "serverUrl",
            "isConfirmFallbackWhenTooManyChanges", "isConfirmFallbackWhenNoFileChanges",
            "isAlwaysRestartAppAfterDeployment", "isAutoFallbackToGradleWhenDeployError",
            "isEmbeddedToApk", "isCheckChecksumWhenFileChanges", "isEnableBackupClasspath_v2",
            "isIgnoreWontCompileModules", "deviceCompatRecordJson").forEach { key ->
            savedValues[key] = globalProperties.getValue("jugg.$key")
        }
        JuggSettings.compileOnSave = false
        JuggSettings.deployOnSave = false
    }

    @After
    fun tearDown() {
        savedValues.forEach { (key, value) ->
            if (value == null) globalProperties.unsetValue("jugg.$key")
            else globalProperties.setValue("jugg.$key", value)
        }
    }

    @Test
    fun `first version applies settings and same version preserves user changes`() {
        val project = project()
        val applier = ProjectDefaultSettingsApplier(project, TestGlobal.getLogger())
        val config = config(2, mapOf("compileOnSave" to true, "deployOnSave" to true))

        assertTrue(applier.apply(config))
        assertTrue(JuggSettings.compileOnSave)
        assertTrue(JuggSettings.deployOnSave)
        JuggSettings.compileOnSave = false

        assertFalse(applier.apply(config))
        assertFalse(JuggSettings.compileOnSave)
    }

    @Test
    fun `upgrade and rollback both reapply defaults`() {
        val applier = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        applier.apply(config(2, mapOf("compileOnSave" to true)))
        applier.apply(config(3, mapOf("compileOnSave" to false)))
        assertFalse(JuggSettings.compileOnSave)

        applier.apply(config(2, mapOf("compileOnSave" to true)))
        assertTrue(JuggSettings.compileOnSave)
    }

    @Test
    fun `missing unknown and invalid entries do not affect other settings`() {
        val applier = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        JuggSettings.deployOnSave = true
        assertTrue(applier.apply(config(2, mapOf(
            "compileOnSave" to true,
            "deployOnSave" to "false",
            "unknownSetting" to true,
        ))))
        assertTrue(JuggSettings.compileOnSave)
        assertTrue(JuggSettings.deployOnSave)
    }

    @Test
    fun `null version or settings does not apply or record version`() {
        val project = project()
        val applier = ProjectDefaultSettingsApplier(project, TestGlobal.getLogger())
        assertFalse(applier.apply(config(null, mapOf("compileOnSave" to true))))
        assertFalse(applier.apply(config(2, null)))
        assertFalse(JuggSettings.compileOnSave)
        assertNull(PropertiesComponent.getInstance(project).getValue("jugg.defaultSettingsVersion"))
    }

    @Test
    fun `applied versions belong to each project`() {
        val first = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        val second = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        first.apply(config(2, mapOf("compileOnSave" to true)))
        JuggSettings.compileOnSave = false

        assertTrue(second.apply(config(2, mapOf("compileOnSave" to true))))
        assertTrue(JuggSettings.compileOnSave)
    }

    @Test
    fun `reflection reads primitive property types and rejects lossy numbers`() {
        val applier = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        val originalMaxModules = JuggSettings.maxCompileSourceModules
        try {
            JuggSettings.serverExpireTimeMill = 9L
            applier.apply(config(2, mapOf(
                "serverExpireTimeMill" to 42.0,
                "maxCompileSourceModules" to 12.0,
                "serverUrl" to "https://example.com",
                "isEnableDirectOverlayDeploy" to false,
            )))
            assertEquals(42L, JuggSettings.serverExpireTimeMill)
            assertEquals(12, JuggSettings.maxCompileSourceModules)
            assertEquals("https://example.com", JuggSettings.serverUrl)
            assertFalse(JuggSettings.isEnableDirectOverlayDeploy)

            applier.apply(config(3, mapOf("serverExpireTimeMill" to 42.5, "maxCompileSourceModules" to 12.5)))
            assertEquals(42L, JuggSettings.serverExpireTimeMill)
            assertEquals(12, JuggSettings.maxCompileSourceModules)
        } finally {
            JuggSettings.maxCompileSourceModules = originalMaxModules
        }
    }

    @Test
    fun `all twelve requested Boolean keys write their matching properties`() {
        val settings = listOf(
            "compileOnSave" to { JuggSettings.compileOnSave },
            "deployOnSave" to { JuggSettings.deployOnSave },
            "isConfirmFallbackWhenNoFileChanges" to { JuggSettings.isConfirmFallbackWhenNoFileChanges },
            "isAlwaysRestartAppAfterDeployment" to { JuggSettings.isAlwaysRestartAppAfterDeployment },
            "isAutoFallbackToGradleWhenDeployError" to { JuggSettings.isAutoFallbackToGradleWhenDeployError },
            "isEmbeddedToApk" to { JuggSettings.isEmbeddedToApk },
            "isCheckChecksumWhenFileChanges" to { JuggSettings.isCheckChecksumWhenFileChanges },
            "isEnableDirectOverlayDeploy" to { JuggSettings.isEnableDirectOverlayDeploy },
            "isUseProjectKotlinCompiler" to { JuggSettings.isUseProjectKotlinCompiler },
            "isEnableBackupClasspath" to { JuggSettings.isEnableBackupClasspath },
            "isIgnoreWontCompileModules" to { JuggSettings.isIgnoreWontCompileModules },
        )
        val applier = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        applier.apply(config(1, settings.associate { it.first to false }))
        applier.apply(config(2, settings.associate { it.first to true }))
        settings.forEach { (key, read) -> assertTrue(key, read()) }
    }

    @Test
    fun `invalid values and read-only properties do not block later entries`() {
        val applierProject = project()
        val applier = ProjectDefaultSettingsApplier(applierProject, TestGlobal.getLogger())
        JuggSettings.serverExpireTimeMill = 11L
        applier.apply(config(1, linkedMapOf(
            "serverExpireTimeMill" to 1e100,
            "isEnableCompatibleDeploymentMode" to false,
            "defaultCompileSettingsJson" to "corrupt",
            "deviceCompatRecordJson" to 123,
            "compileOnSave" to true,
        )))
        assertEquals(11L, JuggSettings.serverExpireTimeMill)
        assertTrue(JuggSettings.isEnableCompatibleDeploymentMode)
        assertTrue(JuggSettings.compileOnSave)
        assertEquals("1", PropertiesComponent.getInstance(applierProject).getValue("jugg.defaultSettingsVersion"))
        JuggSettings.serverUrl = "https://existing.example.com"
        applier.apply(config(2, mapOf("serverUrl" to null)))
        assertEquals("https://existing.example.com", JuggSettings.serverUrl)
    }

    @Test
    fun `failed version persistence leaves version eligible for retry`() {
        val properties = Mockito.mock(PropertiesComponent::class.java)
        val project = object : MockProject(null, {}) {
            init { registerService(PropertiesComponent::class.java, properties) }
        }
        Mockito.doThrow(IllegalStateException("storage unavailable"))
            .`when`(properties).setValue("jugg.defaultSettingsVersion", "2")
        val applier = ProjectDefaultSettingsApplier(project, TestGlobal.getLogger())
        try {
            applier.apply(config(2, mapOf("compileOnSave" to true)))
            fail("Expected storage failure")
        } catch (_: IllegalStateException) {
            assertNull(properties.getValue("jugg.defaultSettingsVersion"))
        }
        Mockito.reset(properties)
        JuggSettings.compileOnSave = false
        assertTrue(applier.apply(config(2, mapOf("compileOnSave" to true))))
        assertTrue(JuggSettings.compileOnSave)
    }

    @Test
    fun `direct Long input retains precision without a Double round trip`() {
        val applier = ProjectDefaultSettingsApplier(project(), TestGlobal.getLogger())
        applier.apply(ProjectCustomConfig(
            buildFileList = emptyList(),
            buildFileRules = emptyList(),
            dontFilterIgnoredFileRules = emptyList(),
            moduleCustomConfigs = null,
            customCompilers = null,
            embeddedApksSearchRules = null,
            defaultSettingsVersion = 1,
            defaultSettings = mapOf("serverExpireTimeMill" to 9_007_199_254_740_993L),
        ))
        assertEquals(9_007_199_254_740_993L, JuggSettings.serverExpireTimeMill)
    }

    @Test
    fun `each successful and failed setting writes a debug log`() {
        val logger = Mockito.mock(com.intellij.openapi.diagnostic.Logger::class.java)
        val applier = ProjectDefaultSettingsApplier(project(), logger)
        applier.apply(config(1, linkedMapOf(
            "compileOnSave" to true,
            "deployOnSave" to "invalid",
            "unknownSetting" to true,
        )))

        Mockito.verify(logger).debug("Applied project default setting compileOnSave")
        Mockito.verify(logger).debug(
            "Failed to apply project default setting deployOnSave: incompatible value type",
        )
        Mockito.verify(logger).debug(
            "Failed to apply project default setting unknownSetting: writable property not found",
        )
    }

    private fun project() = object : MockProject(null, {}) {
        init { registerService(PropertiesComponent::class.java, DummyPropertiesComponent()) }
    }

    private fun config(version: Int?, settings: Map<String, Any?>?): ProjectCustomConfig {
        val json = Gson().toJson(mapOf("defaultSettingsVersion" to version, "defaultSettings" to settings))
        return Gson().fromJson(json, ProjectCustomConfig::class.java)
    }
}
