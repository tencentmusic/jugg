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
        File(userHome, ".claude-internal").mkdirs()

        val summary = JuggHookInstaller.installForClients(
            clients = setOf(
                InstallClient.CLAUDE,
                InstallClient.CODEX,
                InstallClient.CODEBUDDY,
                InstallClient.CURSOR,
                InstallClient.GEMINI,
            ),
            userHome = userHome,
            logger = logger,
        )

        assertEquals(9, summary.results.size)
        assertTrue(summary.results.all { it.status == "ok" })

        assertNestedHookCommands(
            settingsFile = File(userHome, ".claude/settings.json"),
            startEventName = "UserPromptSubmit",
            stopEventName = "Stop",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "claude",
        )
        assertNestedToolHookCommands(
            settingsFile = File(userHome, ".claude/settings.json"),
            editEventName = "PostToolUse",
            commandEventName = "PreToolUse",
            editCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            commandCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            clientArgument = "claude",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".claude/settings.json"),
            eventName = "PostToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "Edit|Write",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".claude/settings.json"),
            eventName = "PreToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            matcher = "Bash",
        )
        assertNestedHookCommands(
            settingsFile = File(userHome, ".codex/hooks.json"),
            startEventName = "UserPromptSubmit",
            stopEventName = "Stop",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "codex",
        )
        assertNestedToolHookCommands(
            settingsFile = File(userHome, ".codex/hooks.json"),
            editEventName = "PostToolUse",
            commandEventName = "PreToolUse",
            editCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            commandCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            clientArgument = "codex",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".codex/hooks.json"),
            eventName = "PostToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "Edit|Write|apply_patch",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".codex/hooks.json"),
            eventName = "PreToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            matcher = "Bash",
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
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            eventName = "Stop",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            matcher = "",
        )
        assertNestedToolHookCommands(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            editEventName = "PostToolUse",
            commandEventName = "PreToolUse",
            editCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            commandCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            clientArgument = "codebuddy",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            eventName = "PostToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "Edit|Write",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".codebuddy/settings.json"),
            eventName = "PreToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            matcher = "Bash",
        )
        assertNestedHookCommands(
            settingsFile = File(userHome, ".gemini/settings.json"),
            startEventName = "BeforeAgent",
            stopEventName = "AfterAgent",
            startCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}start.py",
            stopCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}stop.py",
            clientArgument = "gemini",
        )
        assertNestedToolHookCommands(
            settingsFile = File(userHome, ".gemini/settings.json"),
            editEventName = "AfterTool",
            commandEventName = "BeforeTool",
            editCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            commandCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            clientArgument = "gemini",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".gemini/settings.json"),
            eventName = "AfterTool",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "write_file|replace",
        )
        assertNestedCommandMatcher(
            settingsFile = File(userHome, ".gemini/settings.json"),
            eventName = "BeforeTool",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            matcher = "run_shell_command",
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
        assertFlatToolHookCommands(
            settingsFile = File(userHome, ".cursor/hooks.json"),
            editEventName = "afterFileEdit",
            commandEventName = "beforeShellExecution",
            editCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            commandCommandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            clientArgument = "cursor",
        )
        assertFlatCommandMatcher(
            settingsFile = File(userHome, ".cursor/hooks.json"),
            eventName = "afterFileEdit",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "Write",
        )
        assertNoFlatCommandWithMatcher(
            settingsFile = File(userHome, ".cursor/hooks.json"),
            eventName = "afterFileEdit",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "*",
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

    @Test
    fun installForClients_codex_shouldMigrateLegacyWildcardToolMatchers() {
        val userHome = Files.createTempDirectory("jugg-home-hooks-codex-migrate").toFile()
        val settingsFile = File(userHome, ".codex/hooks.json")
        settingsFile.parentFile.mkdirs()
        val legacyEditCommand = "python3 ${File(userHome, ".jugg/skills/hooks/edit.py").absolutePath} --client codex"
        val legacyCommandCommand = "python3 ${File(userHome, ".jugg/skills/hooks/command.py").absolutePath} --client codex"
        settingsFile.writeText(
            """
            {
              "hooks": {
                "PostToolUse": [
                  {
                    "matcher": "*",
                    "hooks": [
                      {"type": "command", "command": "$legacyEditCommand"},
                      {"type": "command", "command": "python3 /tmp/keep_edit.py"}
                    ]
                  }
                ],
                "PreToolUse": [
                  {
                    "matcher": "*",
                    "hooks": [
                      {"type": "command", "command": "$legacyCommandCommand"},
                      {"type": "command", "command": "python3 /tmp/keep_command.py"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val summary = JuggHookInstaller.installForClients(
            clients = setOf(InstallClient.CODEX),
            userHome = userHome,
            logger = logger,
        )

        assertTrue(summary.results.all { it.status == "ok" || it.status == "already_installed" })
        assertNestedCommandMatcher(
            settingsFile = settingsFile,
            eventName = "PostToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "Edit|Write|apply_patch",
        )
        assertNestedCommandMatcher(
            settingsFile = settingsFile,
            eventName = "PreToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            matcher = "Bash",
        )
        assertNoNestedCommandWithMatcher(
            settingsFile = settingsFile,
            eventName = "PostToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}edit.py",
            matcher = "*",
        )
        assertNoNestedCommandWithMatcher(
            settingsFile = settingsFile,
            eventName = "PreToolUse",
            commandSuffix = "${File.separator}.jugg${File.separator}skills${File.separator}hooks${File.separator}command.py",
            matcher = "*",
        )
        val content = settingsFile.readText()
        assertTrue(content.contains("python3 /tmp/keep_edit.py"))
        assertTrue(content.contains("python3 /tmp/keep_command.py"))
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

    private fun assertNestedToolHookCommands(
        settingsFile: File,
        editEventName: String,
        commandEventName: String,
        editCommandSuffix: String,
        commandCommandSuffix: String,
        clientArgument: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val hooks = root.getAsJsonObject("hooks")
        assertTrue(findNestedCommand(hooks.getAsJsonArray(editEventName), editCommandSuffix, clientArgument))
        assertTrue(findNestedCommand(hooks.getAsJsonArray(commandEventName), commandCommandSuffix, clientArgument))
    }

    private fun assertFlatToolHookCommands(
        settingsFile: File,
        editEventName: String,
        commandEventName: String,
        editCommandSuffix: String,
        commandCommandSuffix: String,
        clientArgument: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val hooks = root.getAsJsonObject("hooks")
        assertTrue(findFlatCommand(hooks.getAsJsonArray(editEventName), editCommandSuffix, clientArgument))
        assertTrue(findFlatCommand(hooks.getAsJsonArray(commandEventName), commandCommandSuffix, clientArgument))
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

    private fun assertNestedCommandMatcher(
        settingsFile: File,
        eventName: String,
        commandSuffix: String,
        matcher: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val eventArray = root.getAsJsonObject("hooks").getAsJsonArray(eventName)
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val itemObj = item.asJsonObject
            if (itemObj.get("matcher")?.asString != matcher) {
                continue
            }
            val hooks = itemObj.getAsJsonArray("hooks")
            for (hook in hooks) {
                if (!hook.isJsonObject) {
                    continue
                }
                val command = hook.asJsonObject.get("command")?.asString ?: continue
                if (command.contains(commandSuffix)) {
                    return
                }
            }
        }
        throw AssertionError("missing matcher=$matcher command=$commandSuffix in ${settingsFile.path}#${eventName}")
    }

    private fun assertNoNestedCommandWithMatcher(
        settingsFile: File,
        eventName: String,
        commandSuffix: String,
        matcher: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val eventArray = root.getAsJsonObject("hooks").getAsJsonArray(eventName)
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val itemObj = item.asJsonObject
            if (itemObj.get("matcher")?.asString != matcher) {
                continue
            }
            val hooks = itemObj.getAsJsonArray("hooks")
            for (hook in hooks) {
                if (!hook.isJsonObject) {
                    continue
                }
                val command = hook.asJsonObject.get("command")?.asString ?: continue
                if (command.contains(commandSuffix)) {
                    throw AssertionError("unexpected matcher=$matcher command=$commandSuffix in ${settingsFile.path}#${eventName}")
                }
            }
        }
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

    private fun assertFlatCommandMatcher(
        settingsFile: File,
        eventName: String,
        commandSuffix: String,
        matcher: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val eventArray = root.getAsJsonObject("hooks").getAsJsonArray(eventName)
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val itemObject = item.asJsonObject
            if (itemObject.get("matcher")?.asString != matcher) {
                continue
            }
            val command = itemObject.get("command")?.asString ?: continue
            if (command.contains(commandSuffix)) {
                return
            }
        }
        throw AssertionError("missing matcher=$matcher command=$commandSuffix in ${settingsFile.path}#$eventName")
    }

    private fun assertNoFlatCommandWithMatcher(
        settingsFile: File,
        eventName: String,
        commandSuffix: String,
        matcher: String,
    ) {
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val eventArray = root.getAsJsonObject("hooks").getAsJsonArray(eventName)
        for (item in eventArray) {
            if (!item.isJsonObject) {
                continue
            }
            val itemObject = item.asJsonObject
            if (itemObject.get("matcher")?.asString != matcher) {
                continue
            }
            val command = itemObject.get("command")?.asString ?: continue
            if (command.contains(commandSuffix)) {
                throw AssertionError("unexpected matcher=$matcher command=$commandSuffix in ${settingsFile.path}#$eventName")
            }
        }
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
