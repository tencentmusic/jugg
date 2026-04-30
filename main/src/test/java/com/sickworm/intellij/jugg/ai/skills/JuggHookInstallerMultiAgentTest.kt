package com.sickworm.intellij.jugg.ai.skills

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class JuggHookInstallerMultiAgentTest {

    private val logger = mock(Logger::class.java)

    @Test
    fun installForClients_shouldInjectHooksForEachClientWithItsOwnConfigStyle() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-multi").toFile()
        // Pre-create internal dirs so that the installer recognises them and injects hooks
        File(userHome, ".gemini-internal").mkdirs()
        File(userHome, ".codex-internal").mkdirs()

        val summary = JuggHookInstaller.installForClients(
            clients = setOf(
                InstallClient.CODEX,
                InstallClient.CODEBUDDY,
                InstallClient.CURSOR,
                InstallClient.GEMINI,
            ),
            userHome = userHome,
            logger = logger,
        )

        assertEquals(7, summary.results.size)
        assertTrue(summary.results.all { it.status == "ok" })

        assertNestedHookCommands(
            settingsFile = File(userHome, ".codex/hooks.json"),
            startEventName = "UserPromptSubmit",
            stopEventName = "Stop",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "codex",
        )
        assertNestedHookCommands(
            settingsFile = File(userHome, ".codex-internal/hooks.json"),
            startEventName = "UserPromptSubmit",
            stopEventName = "Stop",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "codex",
        )
        assertNestedHookCommands(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            startEventName = "UserPromptSubmit",
            stopEventName = "Stop",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "codebuddy",
        )
        assertNestedHookCommands(
            settingsFile = File(userHome, ".gemini/settings.json"),
            startEventName = "BeforeAgent",
            stopEventName = "AfterAgent",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "gemini",
        )
        assertNestedHookCommands(
            settingsFile = File(userHome, ".gemini-internal/hooks.json"),
            startEventName = "BeforeAgent",
            stopEventName = "AfterAgent",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "gemini",
        )
        assertFlatHookCommands(
            settingsFile = File(userHome, ".cursor/hooks.json"),
            startEventName = "beforeSubmitPrompt",
            stopEventName = "stop",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "cursor",
        )
        assertNestedCommandUsesPython3(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            eventName = "UserPromptSubmit",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
        )
        assertNestedCommandUsesPython3(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            eventName = "Stop",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
        )
        assertFlatCommandUsesPython3(
            settingsFile = File(userHome, ".cursor/hooks.json"),
            eventName = "beforeSubmitPrompt",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
        )
        assertFlatCommandUsesPython3(
            settingsFile = File(userHome, ".cursor/hooks.json"),
            eventName = "stop",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
        )
    }

    @Test
    fun installForClients_claude_whenInternalDirAbsent_shouldSkipInternalHooks() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-claude-no-internal").toFile()

        val summary = JuggHookInstaller.installForClients(
            clients = setOf(InstallClient.CLAUDE),
            userHome = userHome,
            logger = logger,
        )

        // Only the primary .claude/settings.json should be written; .claude-internal must not be created
        assertEquals(1, summary.results.size)
        assertTrue(summary.results.all { it.status == "ok" })
        assertFalse(File(userHome, ".claude-internal/settings.json").exists())
    }

    @Test
    fun installForClients_gemini_whenInternalDirAbsent_shouldSkipInternalHooks() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-gemini-no-internal").toFile()

        val summary = JuggHookInstaller.installForClients(
            clients = setOf(InstallClient.GEMINI),
            userHome = userHome,
            logger = logger,
        )

        // Only the primary .gemini/settings.json should be written; .gemini-internal must not be created
        assertEquals(1, summary.results.size)
        assertTrue(summary.results.all { it.status == "ok" })
        assertFalse(File(userHome, ".gemini-internal/hooks.json").exists())
    }

    @Test
    fun installForClients_codex_whenInternalDirAbsent_shouldSkipInternalHooks() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-codex-no-internal").toFile()

        val summary = JuggHookInstaller.installForClients(
            clients = setOf(InstallClient.CODEX),
            userHome = userHome,
            logger = logger,
        )

        // Only the primary .codex/hooks.json should be written; .codex-internal must not be created
        assertEquals(1, summary.results.size)
        assertTrue(summary.results.all { it.status == "ok" })
        assertFalse(File(userHome, ".codex-internal/hooks.json").exists())
    }

    @Test
    fun installForClients_whenEmptySelection_shouldSkipHookConfigInjection() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-empty").toFile()

        val summary = JuggHookInstaller.installForClients(
            clients = emptySet(),
            userHome = userHome,
            logger = logger,
        )

        assertTrue(summary.results.isEmpty())
        assertFalse(File(userHome, ".claude/settings.json").exists())
        assertFalse(File(userHome, ".claude-internal/settings.json").exists())
    }

    private fun assertNestedHookCommands(
        settingsFile: File,
        startEventName: String,
        stopEventName: String,
        startCommandSuffix: String,
        stopCommandSuffix: String,
        clientArgument: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val hooks = root.getAsJsonObject("hooks")
        val startEvent = hooks.getAsJsonArray(startEventName)
        val stopEvent = hooks.getAsJsonArray(stopEventName)
        assertTrue(findNestedCommand(startEvent, startCommandSuffix, clientArgument))
        assertTrue(findNestedCommand(stopEvent, stopCommandSuffix, clientArgument))
    }

    private fun assertFlatHookCommands(
        settingsFile: File,
        startEventName: String,
        stopEventName: String,
        startCommandSuffix: String,
        stopCommandSuffix: String,
        clientArgument: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val hooks = root.getAsJsonObject("hooks")
        val startEvent = hooks.getAsJsonArray(startEventName)
        val stopEvent = hooks.getAsJsonArray(stopEventName)
        assertTrue(findFlatCommand(startEvent, startCommandSuffix, clientArgument))
        assertTrue(findFlatCommand(stopEvent, stopCommandSuffix, clientArgument))
    }

    private fun findNestedCommand(eventArray: JsonArray, commandSuffix: String, clientArgument: String): Boolean {
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val hookArray = item.asJsonObject.getAsJsonArray("hooks")
            for (hook in hookArray) {
                if (!hook.isJsonObject) {
                    continue
                }
                val command = hook.asJsonObject.get("command")?.asString ?: continue
                if (command.contains(commandSuffix) && command.endsWith("--client $clientArgument")) {
                    return true
                }
            }
        }
        return false
    }

    private fun findFlatCommand(eventArray: JsonArray, commandSuffix: String, clientArgument: String): Boolean {
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val itemObject: JsonObject = item.asJsonObject
            val command = itemObject.get("command")?.asString ?: continue
            if (command.contains(commandSuffix) && command.endsWith("--client $clientArgument")) {
                return true
            }
        }
        return false
    }

    private fun assertNestedCommandUsesPython3(
        settingsFile: File,
        eventName: String,
        commandSuffix: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val eventArray = root.getAsJsonObject("hooks").getAsJsonArray(eventName)
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val hooks = item.asJsonObject.getAsJsonArray("hooks")
            for (hook in hooks) {
                if (!hook.isJsonObject) {
                    continue
                }
                val command = hook.asJsonObject.get("command")?.asString ?: continue
                if (command.contains(commandSuffix)) {
                    assertTrue("command should start with python3: $command", command.startsWith("python3 "))
                    return
                }
            }
        }
        throw AssertionError("missing command with suffix $commandSuffix in ${settingsFile.path}")
    }

    private fun assertFlatCommandUsesPython3(
        settingsFile: File,
        eventName: String,
        commandSuffix: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val eventArray = root.getAsJsonObject("hooks").getAsJsonArray(eventName)
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val command = item.asJsonObject.get("command")?.asString ?: continue
            if (command.contains(commandSuffix)) {
                assertTrue("command should start with python3: $command", command.startsWith("python3 "))
                return
            }
        }
        throw AssertionError("missing command with suffix $commandSuffix in ${settingsFile.path}")
    }
}
