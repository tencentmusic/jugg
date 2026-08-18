package com.sickworm.intellij.jugg.ide.bean

import com.intellij.ide.util.PropertiesComponent
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JuggSettingsTest {

    @Test
    fun `compat deploy setting should be persisted`() {
        TestGlobal.init()
        try {
            JuggSettings.isEnableCompatibleDeploymentMode = false

            assertFalse(PropertiesComponent.getInstance()
                .getBoolean("jugg.isEnableCompatibleDeploymentMode", true))
        } finally {
            JuggSettings.isEnableCompatibleDeploymentMode = true
        }
    }

    @Test
    fun `remote command history should be isolated deduplicated and limited`() {
        TestGlobal.init()
        val properties = PropertiesComponent.getInstance()
        val historyKey = "jugg.remoteCommandHistoryJson"
        val oldHistory = properties.getValue(historyKey)
        properties.unsetValue(historyKey)
        try {
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
            properties.setValue(historyKey, oldHistory)
        }
    }

    @Test
    fun `remote command history should ignore blank commands and corrupted data`() {
        TestGlobal.init()
        val properties = PropertiesComponent.getInstance()
        val historyKey = "jugg.remoteCommandHistoryJson"
        val oldHistory = properties.getValue(historyKey)
        properties.setValue(historyKey, "not-json")
        try {
            assertEquals(emptyList<String>(), JuggSettings.getRemoteCommandHistory("target"))

            JuggSettings.recordRemoteCommand("target", "  ")

            assertEquals(emptyList<String>(), JuggSettings.getRemoteCommandHistory("target"))
        } finally {
            properties.setValue(historyKey, oldHistory)
        }
    }
}
