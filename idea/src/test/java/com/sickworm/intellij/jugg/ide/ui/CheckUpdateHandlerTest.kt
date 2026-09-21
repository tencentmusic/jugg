package com.sickworm.intellij.jugg.ide.ui

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.DummyPropertiesComponent
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.CustomConfigManager
import com.sickworm.intellij.jugg.server.protocols.ProjectCustomConfig
import com.sickworm.intellij.jugg.server.protocols.VersionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class CheckUpdateHandlerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `custom config persists before default settings refresh`() {
        TestGlobal.init()
        val project = object : MockProject(null, {}) {
            init { registerService(PropertiesComponent::class.java, DummyPropertiesComponent()) }
        }
        val directory = folder.newFolder()
        val manager = CustomConfigManager(directory, TestGlobal.getLogger())
        val config = Gson().fromJson(
            """{"defaultSettingsVersion":2,"defaultSettings":{"compileOnSave":true}}""",
            ProjectCustomConfig::class.java,
        )
        val original = JuggSettings.compileOnSave
        try {
            var refreshCount = 0
            CheckUpdateHandler(project, "3.6", manager, TestGlobal.getLogger(), {
                assertEquals(2, manager.config?.defaultSettingsVersion)
                refreshCount++
            }).handle(
                VersionData("3.6", false, "", emptyList(), null, config),
            )
            assertTrue(JuggSettings.compileOnSave)
            assertEquals("2", PropertiesComponent.getInstance(project).getValue("jugg.defaultSettingsVersion"))
            assertEquals(2, manager.config?.defaultSettingsVersion)
            assertEquals(1, refreshCount)
        } finally {
            JuggSettings.compileOnSave = original
        }
    }
}
