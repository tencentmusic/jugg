package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggServerChooserTest {
    private var oldServerUrl: String? = null
    private var oldServerExpireTimeMill: Long = 0

    @Before
    fun setUp() {
        TestGlobal.init()
        oldServerUrl = JuggSettings.serverUrl
        oldServerExpireTimeMill = JuggSettings.serverExpireTimeMill
    }

    @After
    fun tearDown() {
        JuggSettings.serverUrl = oldServerUrl
        JuggSettings.serverExpireTimeMill = oldServerExpireTimeMill
    }

    @Test
    fun `custom server remains available without embedded servers`() {
        JuggSettings.serverUrl = "https://custom.example.com"
        JuggSettings.serverExpireTimeMill = -1L

        assertTrue(JuggServerChooser(TestGlobal.getLogger()).hasAvailableServer())
    }

    @Test
    fun `previous selected server is unavailable without embedded servers`() {
        JuggSettings.serverUrl = "https://previous.example.com"
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + 60_000L

        assertFalse(JuggServerChooser(TestGlobal.getLogger()).hasAvailableServer())
    }
}
