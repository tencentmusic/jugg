package com.sickworm.intellij.jugg.ide.logic

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class JuggHookInstallerTest {

    private val logger = mock(Logger::class.java)
    private val legacyStopPrompt = "If any Android code was modified in this session and this is not a subagent session, " +
        "you must enable the jugg-android-dev-loop skill and complete modification verification before stopping. " +
        "If this requirement is not satisfied, return block with a reason; otherwise return approve."

    @Test
    fun installForClaude_shouldCreateSettingsFilesWhenMissing() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-create").toFile()

        val summary = JuggHookInstaller.installForClaude(userHome, logger)

        assertTrue(summary.results.all { it.status == "ok" })
        assertTrue(File(userHome, ".claude/settings.json").exists())
        assertTrue(File(userHome, ".claude-internal/settings.json").exists())
        assertStartCommandHookExists(File(userHome, ".claude/settings.json"))
        assertStopCommandHookExists(File(userHome, ".claude/settings.json"))
        assertStartCommandHookExists(File(userHome, ".claude-internal/settings.json"))
        assertStopCommandHookExists(File(userHome, ".claude-internal/settings.json"))
    }

    @Test
    fun installForClaude_shouldBeIdempotentWithoutDuplicateHookCommand() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-idempotent").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText(
            """
            {
              "hooks": {
                "Stop": [
                  {
                    "matcher": "*",
                    "hooks": [
                      {"type": "command", "command": "${File(userHome, ".jugg/hooks/stop.py").absolutePath}"}
                    ]
                  }
                ],
                "SessionStart": [
                  {
                    "hooks": [
                      {"type": "command", "command": "${File(userHome, ".jugg/hooks/start.py").absolutePath}"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        JuggHookInstaller.installForClaude(userHome, logger)
        JuggHookInstaller.installForClaude(userHome, logger)

        assertEquals(1, countStopCommandHooks(settingsFile))
        assertEquals(1, countSessionStartCommandHooks(settingsFile))
    }

    @Test
    fun installForClaude_shouldPreserveExistingConfigFields() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-preserve").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText(
            """
            {
              "theme": "dark",
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Read", "hooks": [{"type":"command", "command":"echo pre"}]}
                ]
              }
            }
            """.trimIndent()
        )

        JuggHookInstaller.installForClaude(userHome, logger)

        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        assertEquals("dark", root.get("theme").asString)
        assertTrue(root.getAsJsonObject("hooks").has("PreToolUse"))
        assertStartCommandHookExists(settingsFile)
        assertStopCommandHookExists(settingsFile)
    }

    @Test
    fun installForClaude_shouldKeepLegacyStopPromptHook() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-remove-legacy").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText(
            """
            {
              "hooks": {
                "Stop": [
                  {
                    "matcher": "*",
                    "hooks": [
                      {"type": "prompt", "prompt": "$legacyStopPrompt"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        JuggHookInstaller.installForClaude(userHome, logger)

        assertEquals(1, countLegacyStopPromptHooks(settingsFile))
        assertEquals(1, countStopCommandHooks(settingsFile))
    }

    @Test
    fun installForClaude_shouldBackupExistingFileBeforeWrite() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-backup").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText("{\"hooks\":{}}")

        JuggHookInstaller.installForClaude(userHome, logger)

        val backupFile = File(userHome, ".claude/settings.json.bak")
        assertTrue(backupFile.exists())
        assertEquals("{\"hooks\":{}}", backupFile.readText())
    }

    @Test
    fun installForClaude_shouldFailAndKeepOriginalWhenJsonInvalid() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-invalid").toFile()
        val settingsFile = File(userHome, ".claude/settings.json").also { it.parentFile.mkdirs() }
        settingsFile.writeText("{invalid-json}")

        val summary = JuggHookInstaller.installForClaude(userHome, logger)

        assertTrue(summary.results.any { it.path.endsWith(".claude/settings.json") && it.status == "fail" })
        assertEquals("{invalid-json}", settingsFile.readText())
        assertFalse(File(userHome, ".claude/settings.json.bak").exists())
    }

    @Test
    fun installOptions_shouldNotBeEmptyWhenOnlyHooksSelected() {
        val options = InstallOptions(
            clients = emptySet(),
            installCli = false,
            installHooks = true,
        )

        assertFalse(options.isEmpty)
    }

    private fun assertStopCommandHookExists(file: File) {
        val count = countStopCommandHooks(file)
        assertTrue("missing stop command hook in ${file.path}\n${file.readText()}", count >= 1)
    }

    private fun assertStartCommandHookExists(file: File) {
        val count = countSessionStartCommandHooks(file)
        assertTrue("missing SessionStart command hook in ${file.path}\n${file.readText()}", count >= 1)
    }

    private fun countStopCommandHooks(file: File): Int {
        return countCommandHooks(
            file = file,
            eventName = "Stop",
            matcher = "*",
            commandSuffix = "${File.separator}.jugg${File.separator}hooks${File.separator}stop.py",
        )
    }

    private fun countSessionStartCommandHooks(file: File): Int {
        return countCommandHooks(
            file = file,
            eventName = "SessionStart",
            matcher = null,
            commandSuffix = "${File.separator}.jugg${File.separator}hooks${File.separator}start.py",
        )
    }

    private fun countLegacyStopPromptHooks(file: File): Int {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val stopHooks = root
            .getAsJsonObject("hooks")
            .getAsJsonArray("Stop")
        var count = 0
        stopHooks.forEach { item ->
            val hooks = item.asJsonObject.getAsJsonArray("hooks")
            hooks.forEach { hook ->
                val hookObj = hook.asJsonObject
                if (hookObj.get("type")?.asString == "prompt" &&
                    hookObj.get("prompt")?.asString == legacyStopPrompt
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun countCommandHooks(
        file: File,
        eventName: String,
        matcher: String?,
        commandSuffix: String,
    ): Int {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val hooksByEvent = root
            .getAsJsonObject("hooks")
            .getAsJsonArray(eventName)
        var count = 0
        hooksByEvent.forEach { item ->
            val itemObj = item.asJsonObject
            if (matcher != null && itemObj.get("matcher")?.asString != matcher) {
                return@forEach
            }
            val hooks = itemObj.getAsJsonArray("hooks")
            hooks.forEach { hook ->
                val hookObj = hook.asJsonObject
                if (hookObj.get("type")?.asString == "command" &&
                    hookObj.get("command")?.asString?.endsWith(commandSuffix) == true
                ) {
                    count++
                }
            }
        }
        return count
    }
}
