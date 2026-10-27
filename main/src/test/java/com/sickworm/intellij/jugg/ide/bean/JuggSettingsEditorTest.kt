package com.sickworm.intellij.jugg.ide.bean

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Protects typed editing, confirmation, and atomic shared-setting changes. */
class JuggSettingsEditorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val originalRoot = JuggGlobalPathManager.rootDir

    @Before fun setUp() { JuggGlobalPathManager.rootDir = temporary.newFolder("settings") }
    @After fun tearDown() { JuggGlobalPathManager.rootDir = originalRoot; JuggSettings.reload() }

    @Test fun `typed edits preserve unrelated fields and reject overflow without writing`() {
        JuggGlobalPathManager.settingsFile.writeText("""{"unknown":{"keep":true}}""")
        val editor = JuggSettingsEditor()
        assertEquals(30, (editor.set("maxCompileSourceModules", "30")["value"] as JsonElement).asInt)
        val before = JuggGlobalPathManager.settingsFile.readText()
        assertFailsWith<IllegalArgumentException> { editor.set("maxCompileSourceModules", "2147483648") }
        assertEquals(before, JuggGlobalPathManager.settingsFile.readText())
        assertTrue(JsonParser.parseString(before).asJsonObject.getAsJsonObject("unknown").get("keep").asBoolean)
    }

    @Test fun `confirmation is required before backup setting or effect changes`() {
        val editor = JuggSettingsEditor()
        val failure = assertFailsWith<SettingConfirmationRequired> {
            editor.set("isEnableBackupClasspath", "true")
        }
        assertEquals("Confirm Switch Backup Classpath", failure.confirmation.title)
        assertFalse(JuggGlobalPathManager.settingsFile.exists())
        editor.set("isEnableBackupClasspath", "true", confirm = true)
        assertTrue(JuggSettings.isEnableBackupClasspath)
        assertEquals(1L, JuggSettings.snapshot().get("backupClasspathRevision").asLong)
        assertEquals(false, editor.set("isEnableBackupClasspath", "true", confirm = true)["changed"])
        assertEquals(1L, JuggSettings.snapshot().get("backupClasspathRevision").asLong)
    }

    @Test fun `list has descriptions while get and set do not`() {
        val editor = JuggSettingsEditor()
        val items = editor.list()["items"] as List<*>
        assertTrue(items.isNotEmpty())
        items.forEach { assertEquals(setOf("key", "value", "description"), (it as Map<*, *>).keys) }
        assertFalse(editor.get("compileOnSave").containsKey("description"))
        assertFalse(editor.set("compileOnSave", "true").containsKey("description"))
    }

    @Test fun `custom server needs trust and commits URL and mode together`() {
        val editor = JuggSettingsEditor()
        assertFailsWith<SettingConfirmationRequired> { editor.set("serverUrl", "https://example.com") }
        editor.set("serverUrl", "https://example.com", confirm = true)
        assertEquals("https://example.com", JuggSettings.serverUrl)
        assertEquals(-1L, JuggSettings.serverExpireTimeMill)
        editor.set("serverExpireTimeMill", "0")
        assertFailsWith<SettingConfirmationRequired> { editor.set("serverExpireTimeMill", "-1") }
    }

    @Test fun `malformed storage is preserved and external replacements refresh getters`() {
        val editor = JuggSettingsEditor()
        editor.set("compileOnSave", "false")
        assertFalse(JuggSettings.compileOnSave)
        JuggGlobalPathManager.settingsFile.writeText("""{"compileOnSave":true}""")
        assertTrue(JuggSettings.compileOnSave)
        JuggGlobalPathManager.settingsFile.writeText("{broken")
        assertFailsWith<Exception> { editor.set("compileOnSave", "false") }
        assertEquals("{broken", JuggGlobalPathManager.settingsFile.readText())
    }
    @Test fun `structured settings validate before mutation and redact secrets`() {
        val editor = JuggSettingsEditor()
        val result = editor.set("defaultCompileSettings", """{"remoteSshPassword":"secret-value","environmentVariables":"TOKEN=private"}""")
        assertFalse(result.toString().contains("secret-value"))
        assertFalse(result.toString().contains("TOKEN=private"))
        assertEquals("secret-value", JuggSettings.defaultCompileSettings.remoteSshPassword)
        val before = JuggGlobalPathManager.settingsFile.readText()
        for (raw in listOf("""{"isRemoteCompile":"true"}""", """{"remoteSshPort":2147483648}""",
                """{"templateName":null}""", """{"unknown":true}""", """{"remoteSshPassword":"<redacted>"}""")) {
            assertFailsWith<IllegalArgumentException> { editor.set("defaultCompileSettings", raw) }
            assertEquals(before, JuggGlobalPathManager.settingsFile.readText())
        }
        editor.set("deviceCompatRecordJson", """[{"displayName":"Pixel","applications":["example.app"]}]""")
        editor.set("sliceDeployRecordJson", """[{"displayName":"Pixel","firstSliceSize":1,"sliceSize":2}]""")
        assertFailsWith<IllegalArgumentException> { editor.set("sliceDeployRecordJson", """[{"sliceSize":0}]""") }
        assertFailsWith<IllegalArgumentException> { editor.set("deviceCompatRecordJson", """[{"applications":[false]}]""") }
    }

    @Test fun `read only and scalar validation leave storage unchanged`() {
        val editor = JuggSettingsEditor()
        for ((key, value) in listOf("isEmbeddedToApk" to "1", "compileOnSave" to "TRUE",
                "maxCompileSourceModules" to "0", "maxCompileSourceFilePoints" to "1.2",
                "serverExpireTimeMill" to "9223372036854775808", "serverExpireTimeMill" to "-2",
                "isForceEnableBackupClasspath" to "true", "compile-on-save" to "true")) {
            assertFailsWith<IllegalArgumentException> { editor.set(key, value) }
            assertFalse(JuggGlobalPathManager.settingsFile.exists())
        }
        assertFailsWith<SettingConfirmationRequired> { editor.set("isEmbeddedToApk", "true", confirm = false) }
        editor.set("isEmbeddedToApk", "true", confirm = true)
        editor.reset("isEmbeddedToApk")
        assertFalse(JuggSettings.isEmbeddedToApk)
    }

    @Test fun `stale transaction cannot overwrite newer server trust decision`() {
        val repository = com.sickworm.intellij.jugg.project.runtime.JsonRuntimeSettingsRepository(
            JuggGlobalPathManager.settingsFile, JuggGlobalPathManager.rootDir,
            com.sickworm.intellij.jugg.mock.TestGlobal.logger)
        val snapshot = repository.loadStrict()
        JuggSettingsEditor().set("serverUrl", "https://trusted.example", confirm = true)
        assertFailsWith<com.sickworm.intellij.jugg.project.runtime.SettingsChangedException> {
            repository.updateStrict(mapOf("serverUrl" to com.google.gson.JsonPrimitive("https://automatic.example")), snapshot)
        }
        assertEquals("https://trusted.example", JuggSettings.serverUrl)
        assertEquals(-1L, JuggSettings.serverExpireTimeMill)
    }

    @Test fun `IDE renders the same confirmation and cancellation never writes`() {
        val platform = org.mockito.Mockito.mock(com.sickworm.intellij.jugg.platform.IPlatformApi::class.java)
        val previous = runCatching { com.sickworm.intellij.jugg.platform.PlatformApi.impl }.getOrNull()
        com.sickworm.intellij.jugg.platform.PlatformApi.impl = platform
        try {
            val editor = JuggSettingsEditor()
            val failure = assertFailsWith<SettingConfirmationRequired> { editor.set("isEmbeddedToApk", "true") }
            assertFalse(editor.setFromUi("isEmbeddedToApk", "true"))
            org.mockito.Mockito.verify(platform).showDialog(failure.confirmation.title, failure.confirmation.html(), "OK", "Cancel", true)
            assertFalse(JuggGlobalPathManager.settingsFile.exists())
            org.mockito.Mockito.`when`(platform.showDialog(failure.confirmation.title, failure.confirmation.html(), "OK", "Cancel", true)).thenReturn(true)
            assertTrue(editor.setFromUi("isEmbeddedToApk", "true"))
            assertTrue(JuggSettings.isEmbeddedToApk)
        } finally {
            if (previous != null) com.sickworm.intellij.jugg.platform.PlatformApi.impl = previous
        }
    }

    @Test fun `automatic reset retains the previous URL without candidates and rejects malformed JSON`() {
        val chooser = com.sickworm.intellij.jugg.server.JuggServerChooser(com.sickworm.intellij.jugg.mock.TestGlobal.logger)
        val editor = JuggSettingsEditor(chooser)
        editor.set("serverUrl", "https://custom.example", confirm = true)
        chooser.updateServer(emptyList())
        val result = editor.reset("serverUrl")
        assertEquals("https://custom.example", (result["value"] as JsonElement).asString)
        assertEquals("https://custom.example", JuggSettings.serverUrl)
        assertEquals(0L, JuggSettings.serverExpireTimeMill)
        val before = JuggGlobalPathManager.settingsFile.readText()
        assertFailsWith<IllegalArgumentException> { editor.set("defaultCompileSettings", "{templateName:'loose'}") }
        assertEquals(before, JuggGlobalPathManager.settingsFile.readText())
    }

    @Test fun `reset immediately selects an available automatic server and returns its actual URL`() {
        val chooser = com.sickworm.intellij.jugg.server.JuggServerChooser(com.sickworm.intellij.jugg.mock.TestGlobal.logger)
        val editor = JuggSettingsEditor(chooser)
        editor.set("serverUrl", "https://custom.example", confirm = true)
        chooser.updateServer(listOf(com.sickworm.intellij.jugg.server.protocols.ServerRule("https://automatic.example", null)))
        val result = editor.reset("serverUrl")
        assertEquals("https://automatic.example", (result["value"] as JsonElement).asString)
        assertEquals("https://automatic.example", JuggSettings.serverUrl)
        assertTrue(JuggSettings.serverExpireTimeMill > System.currentTimeMillis())
    }

    @Test fun `empty automatic reset persists an explicit null`() {
        val chooser = com.sickworm.intellij.jugg.server.JuggServerChooser(com.sickworm.intellij.jugg.mock.TestGlobal.logger)
        chooser.updateServer(emptyList())
        JuggSettingsEditor(chooser).reset("serverUrl")
        assertTrue(JuggSettings.snapshot().has("serverUrl"))
        assertTrue(JuggSettings.snapshot()["serverUrl"].isJsonNull)
    }

    @Test fun `nullable template fields remain usable by the runtime getter`() {
        JuggSettingsEditor().set("defaultCompileSettings", """{"templateName":"Nullable","remoteSshUser":null}""")
        assertEquals("Nullable", JuggSettings.defaultCompileSettings.templateName)
        assertNull(JuggSettings.defaultCompileSettings.remoteSshUser)
    }

}
