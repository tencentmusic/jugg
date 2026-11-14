package com.sickworm.intellij.jugg.server

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
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
    private var oldInheritedServerRules: String? = null
    private lateinit var originalPlatformApi: IPlatformApi

    @Before
    fun setUp() {
        TestGlobal.init()
        oldServerUrl = JuggSettings.serverUrl
        oldServerExpireTimeMill = JuggSettings.serverExpireTimeMill
        oldInheritedServerRules = PropertiesComponent.getInstance().getValue("jugg.inheritedServerRulesJson")
        PropertiesComponent.getInstance().unsetValue("jugg.inheritedServerRulesJson")
        JuggSettings.serverUrl = null
        JuggSettings.serverExpireTimeMill = 0L
        originalPlatformApi = PlatformApi.impl
    }

    @After
    fun tearDown() {
        JuggSettings.serverUrl = oldServerUrl
        JuggSettings.serverExpireTimeMill = oldServerExpireTimeMill
        PropertiesComponent.getInstance().setValue("jugg.inheritedServerRulesJson", oldInheritedServerRules)
        PlatformApi.impl = originalPlatformApi
    }

    @Test
    fun `custom server remains available without embedded servers`() {
        JuggSettings.serverUrl = "https://custom.example.com"
        JuggSettings.serverExpireTimeMill = -1L

        assertTrue(JuggServerChooser(TestGlobal.getLogger()).hasAvailableServer())
        assertEquals("https://custom.example.com", JuggServerChooser(TestGlobal.getLogger()).availableServerUrl)
        assertEquals("https://custom.example.com/report_issue", JuggServerChooser(TestGlobal.getLogger()).issueReportDestination.uploadUrl)
        assertFalse(JuggServerChooser(TestGlobal.getLogger()).issueReportDestination.redactLogs)
    }

    @Test
    fun `previous selected server is unavailable without embedded servers`() {
        JuggSettings.serverUrl = "https://previous.example.com"
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + 60_000L

        assertFalse(JuggServerChooser(TestGlobal.getLogger()).hasAvailableServer())
        assertEquals(null, JuggServerChooser(TestGlobal.getLogger()).availableServerUrl)
        assertTrue(JuggServerChooser(TestGlobal.getLogger()).issueReportDestination.redactLogs)
    }

    @Test
    fun `automatically selected backend is available for issue reports`() {
        JuggSettings.serverUrl = null
        JuggSettings.serverExpireTimeMill = 0L
        val chooser = JuggServerChooser(TestGlobal.getLogger())

        chooser.updateServer(listOf(ServerRule("http://backend.example.com:12305", null)))

        assertEquals("http://backend.example.com:12305", chooser.availableServerUrl)
        assertEquals("http://backend.example.com:12305/report_issue", chooser.issueReportDestination.uploadUrl)
        assertFalse(chooser.issueReportDestination.redactLogs)

        JuggSettings.serverUrl = "https://previous.example.com"
        assertEquals(null, chooser.availableServerUrl)
    }

    @Test
    fun `forced refresh replaces stale server before first request`() {
        JuggSettings.serverUrl = "http://127.0.0.1:1"
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + 60_000L

        val chooser = chooserWithEmbeddedServers(INTERNAL_SERVERS)
        chooser.javaClass.getMethod("updateServerIfExpired", Boolean::class.javaPrimitiveType).invoke(chooser, true)

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
        assertTrue(confirmationContents.single().contains("unredacted logs"))
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

    @Test
    fun `public upgrade retains bundled server rules and failover without becoming a custom server`() {
        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        JuggSettings.serverUrl = null
        JuggSettings.serverExpireTimeMill = 0L
        val publicChooser = chooserWithEmbeddedServers(null)

        assertTrue(publicChooser.javaClass.getMethod("hasAvailableServer").invoke(publicChooser) as Boolean)
        publicChooser.updateServers()

        assertEquals("https://internal.example.com", publicChooser.availableUrl())
        assertTrue(JuggSettings.serverExpireTimeMill > 0L)
        assertTrue(publicChooser.javaClass.getMethod("updateServerWithForbidCurrentUrl", String::class.java)
            .invoke(publicChooser, "https://internal.example.com") as Boolean)
        assertEquals("https://backup.example.com", publicChooser.availableUrl())
    }

    @Test
    fun `fresh public installation does not adopt a stale selected URL`() {
        JuggSettings.serverUrl = "https://previous.example.com"
        val chooser = chooserWithEmbeddedServers(null)

        chooser.updateServers()

        assertFalse(chooser.javaClass.getMethod("hasAvailableServer").invoke(chooser) as Boolean)
        assertEquals(null, chooser.availableUrl())
    }

    @Test
    fun `custom server remains preferred across internal installation and public upgrade`() {
        JuggSettings.serverUrl = "https://custom.example.com"
        JuggSettings.serverExpireTimeMill = -1L

        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        val publicChooser = chooserWithEmbeddedServers(null)
        publicChooser.updateServers()

        assertEquals("https://custom.example.com", publicChooser.availableUrl())
        assertEquals(-1L, JuggSettings.serverExpireTimeMill)
    }

    @Test
    fun `clearing custom server restores inherited defaults in a public build`() {
        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        JuggSettings.serverUrl = "https://custom.example.com"
        JuggSettings.serverExpireTimeMill = -1L
        val platformApi = Mockito.mock(IPlatformApi::class.java)
        Mockito.`when`(platformApi.showUserAndPasswordInputDialog(
            anyString(), anyString(), anyBoolean(), anyString(), anyString(),
        )).thenReturn("")
        PlatformApi.impl = platformApi
        val publicChooser = chooserWithEmbeddedServers(null)

        publicChooser.javaClass.getMethod("setCustomServer").invoke(publicChooser)

        assertEquals("https://internal.example.com", publicChooser.availableUrl())
        assertTrue(JuggSettings.serverExpireTimeMill > 0L)
    }

    @Test
    fun `new internal build refreshes inherited defaults`() {
        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        chooserWithEmbeddedServers("""[{"url":"https://replacement.example.com"}]""").updateServers()
        val publicChooser = chooserWithEmbeddedServers(null)

        publicChooser.updateServers()

        assertEquals("https://replacement.example.com", publicChooser.availableUrl())
    }

    @Test
    fun `bundled defaults are remembered only when default rules are needed`() {
        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        val internalChooser = chooserWithEmbeddedServers("""[{"url":"https://replacement.example.com"}]""")
        internalChooser.updateServers(listOf(ServerRule("https://project.example.com", null)))
        assertTrue(internalChooser.javaClass.getMethod("hasAvailableServer").invoke(internalChooser) as Boolean)

        val publicChooser = chooserWithEmbeddedServers(null)
        publicChooser.updateServers()
        assertEquals("https://internal.example.com", publicChooser.availableUrl())

        internalChooser.updateServers()
        val nextPublicChooser = chooserWithEmbeddedServers(null)
        nextPublicChooser.updateServers()
        assertEquals("https://replacement.example.com", nextPublicChooser.availableUrl())
    }

    @Test
    fun `project server rules override inherited defaults without replacing them`() {
        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        val publicChooser = chooserWithEmbeddedServers(null)

        publicChooser.updateServers(listOf(ServerRule("https://project.example.com", null)))
        assertEquals("https://project.example.com", publicChooser.availableUrl())
        chooserWithEmbeddedServers(null).also {
            it.updateServers()
            assertEquals("https://internal.example.com", it.availableUrl())
        }
        publicChooser.updateServers(emptyList())
        assertFalse(publicChooser.javaClass.getMethod("hasAvailableServer").invoke(publicChooser) as Boolean)
        assertEquals(null, publicChooser.availableUrl())
    }

    @Test
    fun `corrupt inherited rules do not enable a backend or block bundled rules`() {
        listOf("broken json", "{}", "[null]", "[{}]", """[{"url":""}]""").forEach { json ->
            PropertiesComponent.getInstance().setValue("jugg.inheritedServerRulesJson", json)
            val publicChooser = chooserWithEmbeddedServers(null)
            publicChooser.updateServers()
            assertFalse(publicChooser.javaClass.getMethod("hasAvailableServer").invoke(publicChooser) as Boolean)
            assertEquals(null, publicChooser.availableUrl())
        }

        chooserWithEmbeddedServers(INTERNAL_SERVERS).updateServers()
        val nextPublicChooser = chooserWithEmbeddedServers(null)
        nextPublicChooser.updateServers()
        assertEquals("https://internal.example.com", nextPublicChooser.availableUrl())
    }

    // Load the production chooser with each distribution's resources while sharing IDE settings.
    private fun chooserWithEmbeddedServers(serversJson: String?): Any {
        val className = JuggServerChooser::class.java.name
        val loader = object : ClassLoader(JuggServerChooser::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != className && !name.startsWith("$className$")) return super.loadClass(name, resolve)
                val clazz = findLoadedClass(name) ?: run {
                    val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(clazz)
                return clazz
            }

            override fun getResourceAsStream(name: String) = if (name == "config/servers.json") {
                serversJson?.byteInputStream()
            } else {
                super.getResourceAsStream(name)
            }
        }
        return loader.loadClass(className).getConstructor(Logger::class.java).newInstance(TestGlobal.getLogger())
    }

    private fun Any.updateServers(rules: List<ServerRule>? = null) {
        javaClass.getMethod("updateServer", List::class.java).invoke(this, rules)
    }

    private fun Any.availableUrl(): String? = javaClass.getMethod("getAvailableServerUrl").invoke(this) as String?

    companion object {
        private const val INTERNAL_SERVERS = """[{"url":"https://internal.example.com"},{"url":"https://backup.example.com"}]"""
    }
}
