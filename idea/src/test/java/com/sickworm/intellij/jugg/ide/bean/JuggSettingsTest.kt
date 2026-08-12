package com.sickworm.intellij.jugg.ide.bean

import com.intellij.ide.util.PropertiesComponent
import com.sickworm.intellij.jugg.mock.TestGlobal
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
}
