package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.server.protocols.ServerRule
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JuggServerChooserTest {
    private var oldServerUrl: String? = null
    private var oldServerExpireTimeMill: Long = 0
    private lateinit var originalPlatformApi: IPlatformApi

    @Before
    fun setUp() {
        TestGlobal.init()
        oldServerUrl = JuggSettings.serverUrl
        oldServerExpireTimeMill = JuggSettings.serverExpireTimeMill
        originalPlatformApi = PlatformApi.impl
    }

    @After
    fun tearDown() {
        JuggSettings.serverUrl = oldServerUrl
        JuggSettings.serverExpireTimeMill = oldServerExpireTimeMill
        PlatformApi.impl = originalPlatformApi
    }

    @Test
    fun `custom server remains available without embedded servers`() {
        JuggSettings.serverUrl = "https://custom.example.com"
        JuggSettings.serverExpireTimeMill = -1L

        assertTrue(JuggServerChooser(TestGlobal.getLogger()).hasAvailableServer())
        assertEquals("https://custom.example.com", JuggServerChooser(TestGlobal.getLogger()).availableServerUrl)
    }

    @Test
    fun `previous selected server is unavailable without embedded servers`() {
        JuggSettings.serverUrl = "https://previous.example.com"
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + 60_000L

        assertFalse(JuggServerChooser(TestGlobal.getLogger()).hasAvailableServer())
        assertEquals(null, JuggServerChooser(TestGlobal.getLogger()).availableServerUrl)
    }

    @Test
    fun `automatically selected backend is available for issue reports`() {
        JuggSettings.serverUrl = null
        JuggSettings.serverExpireTimeMill = 0L
        val chooser = JuggServerChooser(TestGlobal.getLogger())

        chooser.updateServer(listOf(ServerRule("http://backend.example.com:12305", null)))

        assertEquals("http://backend.example.com:12305", chooser.availableServerUrl)

        JuggSettings.serverUrl = "https://previous.example.com"
        assertEquals(null, chooser.availableServerUrl)
    }

    @Test
    fun `forced refresh replaces stale server before first request`() {
        JuggSettings.serverUrl = "http://127.0.0.1:1"
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + 60_000L

        JuggServerChooser(TestGlobal.getLogger()).updateServerIfExpired(isForce = true)

        assertTrue(!JuggSettings.serverUrl.isNullOrBlank())
        assertTrue(JuggSettings.serverUrl != "http://127.0.0.1:1")
    }

    @Test
    fun `custom server is persisted only after remote capability confirmation`() {
        val platformApi = Mockito.mock(IPlatformApi::class.java)
        Mockito.`when`(platformApi.showUserAndPasswordInputDialog(
            anyString(), anyString(), anyBoolean(), anyString(), anyString(),
        )).thenReturn("https://trusted.example.com")
        val confirmationContents = mutableListOf<String>()
        Mockito.doAnswer {
            confirmationContents += it.arguments[1] as String
            true
        }.`when`(platformApi).showDialog(
            anyString(), anyString(), anyString(), anyString(), anyBoolean(),
        )
        PlatformApi.impl = platformApi

        JuggServerChooser(TestGlobal.getLogger()).setCustomServer()

        assertEquals("https://trusted.example.com", JuggSettings.serverUrl)
        assertEquals(-1L, JuggSettings.serverExpireTimeMill)
        assertEquals(1, confirmationContents.size)
        assertTrue(confirmationContents.single().contains("https://trusted.example.com"))
        assertTrue(confirmationContents.single().contains("custom compiler JARs"))
    }

    @Test
    fun `custom server is not persisted when remote capability confirmation is cancelled`() {
        JuggSettings.serverUrl = "https://existing.example.com"
        JuggSettings.serverExpireTimeMill = -1L
        val platformApi = Mockito.mock(IPlatformApi::class.java)
        Mockito.`when`(platformApi.showUserAndPasswordInputDialog(
            anyString(), anyString(), anyBoolean(), anyString(), anyString(),
        )).thenReturn("https://untrusted.example.com")
        Mockito.`when`(platformApi.showDialog(
            anyString(), anyString(), anyString(), anyString(), anyBoolean(),
        )).thenReturn(false)
        PlatformApi.impl = platformApi

        JuggServerChooser(TestGlobal.getLogger()).setCustomServer()

        assertEquals("https://existing.example.com", JuggSettings.serverUrl)
        assertEquals(-1L, JuggSettings.serverExpireTimeMill)
    }
}
