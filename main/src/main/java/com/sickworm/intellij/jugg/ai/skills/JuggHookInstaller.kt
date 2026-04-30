package com.sickworm.intellij.jugg.ai.skills

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.skills.agents.AgentHookConfigStyle
import com.sickworm.intellij.jugg.ai.skills.agents.AgentHookTarget
import com.sickworm.intellij.jugg.ai.skills.agents.InstallAgents
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs hook configs that enforce Android-change verification via
 * start/stop command hooks.
 */
object JuggHookInstaller {
    private const val BACKUP_SUFFIX = ".bak"
    private const val MATCHER_ALL = "*"
    private const val PYTHON3_PREFIX = "python3 "
    private const val CLIENT_OPTION = "--client"
    private const val START_HOOK_RELATIVE_PATH = ".jugg/skills/hooks/start.py"
    private const val STOP_HOOK_RELATIVE_PATH = ".jugg/skills/hooks/stop.py"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * Backward-compatible entry for Claude hook installation.
     */
    fun installForClaude(
        userHome: File = File(System.getProperty("user.home")),
        logger: Logger,
    ): HookInstallSummary {
        return installForClients(setOf(InstallClient.CLAUDE), userHome, logger)
    }

    /**
     * Installs hook config for selected clients.
     * If no client is selected, no hook config is injected.
     */
    fun installForClients(
        clients: Set<InstallClient>,
        userHome: File = File(System.getProperty("user.home")),
        logger: Logger,
    ): HookInstallSummary {
        val startScriptPath = File(userHome, START_HOOK_RELATIVE_PATH).absolutePath
        val stopScriptPath = File(userHome, STOP_HOOK_RELATIVE_PATH).absolutePath
        val targets = resolveTargets(clients, userHome, startScriptPath, stopScriptPath)
        val results = targets.map { target ->
            runCatching { installTarget(target) }
                .onFailure { error ->
                    logger.warn("[Install Agent Hooks] failed path=${target.file.path}", error)
                }
                .getOrElse { error ->
                    HookInstallResult(
                        path = target.file.path,
                        status = "fail",
                        reason = error.safeReason(),
                    )
                }
        }
        return HookInstallSummary(results)
    }

    private fun resolveTargets(
        clients: Set<InstallClient>,
        userHome: File,
        startScriptPath: String,
        stopScriptPath: String,
    ): List<HookInstallTarget> {
        if (clients.isEmpty()) {
            return emptyList()
        }
        return clients.toList()
            .sortedBy { it.name }
            .flatMap { client ->
                InstallAgents.resolveAgentInstaller(client)
                    .resolveHookTargets(userHome)
                    .map { target ->
                        HookInstallTarget(
                            file = target.settingsFile,
                            adapter = buildAdapter(
                                target = target,
                                startCommand = buildHookCommand(startScriptPath, target.clientArgument),
                                stopCommand = buildHookCommand(stopScriptPath, target.clientArgument),
                            ),
                        )
                    }
            }
    }

    private fun buildHookCommand(scriptPath: String, clientArgument: String): String {
        return "$PYTHON3_PREFIX$scriptPath $CLIENT_OPTION $clientArgument"
    }

    private fun buildAdapter(target: AgentHookTarget, startCommand: String, stopCommand: String): HookConfigAdapter {
        return when (target.style) {
            AgentHookConfigStyle.NESTED_EVENT_HOOKS -> {
                NestedEventHooksAdapter(
                    startCommand = startCommand,
                    stopCommand = stopCommand,
                    startEventName = target.startEventName,
                    stopEventName = target.stopEventName,
                    startMatcher = null,
                    stopMatcher = MATCHER_ALL,
                )
            }

            AgentHookConfigStyle.FLAT_EVENT_COMMANDS -> {
                FlatEventHooksAdapter(
                    startCommand = startCommand,
                    stopCommand = stopCommand,
                    startEventName = target.startEventName,
                    stopEventName = target.stopEventName,
                    startMatcher = MATCHER_ALL,
                    stopMatcher = MATCHER_ALL,
                )
            }
        }
    }

    private fun installTarget(target: HookInstallTarget): HookInstallResult {
        val existing = if (target.file.exists()) {
            target.file.readText(StandardCharsets.UTF_8)
        } else {
            null
        }
        val mergeResult = target.adapter.merge(existing)
        if (!mergeResult.changed) {
            return HookInstallResult(path = target.file.path, status = "already_installed", reason = null)
        }
        writeAtomicallyWithBackup(target.file, mergeResult.mergedContent)
        return HookInstallResult(path = target.file.path, status = "ok", reason = null)
    }

    private fun writeAtomicallyWithBackup(targetFile: File, content: String) {
        val parent = targetFile.parentFile ?: throw IOException("missing_parent_dir")
        parent.mkdirs()

        val tempFile = File(parent, "${targetFile.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (targetFile.exists()) {
                val backupFile = File(parent, "${targetFile.name}$BACKUP_SUFFIX")
                targetFile.copyTo(backupFile, overwrite = true)
            }
            moveFile(tempFile, targetFile)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun moveFile(tempFile: File, targetFile: File) {
        val sourcePath = tempFile.toPath()
        val targetPath = targetFile.toPath()
        try {
            Files.move(
                sourcePath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun Throwable.safeReason(): String {
        return (message ?: javaClass.simpleName).replace(Regex("\\s+"), "_")
    }

    private data class HookInstallTarget(
        val file: File,
        val adapter: HookConfigAdapter,
    )

    private interface HookConfigAdapter {
        fun merge(existingContent: String?): HookMergeResult
    }

    private data class HookMergeResult(
        val mergedContent: String,
        val changed: Boolean,
    )

    /**
     * JSON adapter for nested event hooks like:
     * hooks -> EventName -> [{ matcher, hooks: [{ type, command }] }]
     */
    private class NestedEventHooksAdapter(
        private val startCommand: String,
        private val stopCommand: String,
        private val startEventName: String,
        private val stopEventName: String,
        private val startMatcher: String?,
        private val stopMatcher: String?,
    ) : HookConfigAdapter {

        override fun merge(existingContent: String?): HookMergeResult {
            val root = parseRootObject(existingContent)
            var changed = false

            val hooks = root.ensureObject("hooks").also { changed = changed || it.second }.first
            val startEvent = hooks.ensureArray(startEventName).also { changed = changed || it.second }.first
            val startEntry = findOrCreateMatcherEntry(startEvent, startMatcher).also { changed = changed || it.second }.first
            val startHookArray = startEntry.ensureArray("hooks").also { changed = changed || it.second }.first
            val startCommandAdded = ensureNestedCommandHook(startHookArray, startCommand)
            changed = changed || startCommandAdded

            val stopEvent = hooks.ensureArray(stopEventName).also { changed = changed || it.second }.first
            val stopEntry = findOrCreateMatcherEntry(stopEvent, stopMatcher).also { changed = changed || it.second }.first
            val stopHookArray = stopEntry.ensureArray("hooks").also { changed = changed || it.second }.first
            val stopCommandAdded = ensureNestedCommandHook(stopHookArray, stopCommand)
            changed = changed || stopCommandAdded

            return HookMergeResult(gson.toJson(root), changed)
        }

        private fun findOrCreateMatcherEntry(eventHooks: JsonArray, matcherValue: String?): Pair<JsonObject, Boolean> {
            eventHooks.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                val matcherElement = obj.get("matcher")
                if (matcherValue == null) {
                    if (matcherElement == null || matcherElement.isJsonNull || matcherElement.asString == MATCHER_ALL) {
                        return obj to false
                    }
                    return@forEach
                }
                if (matcherElement?.asString == matcherValue) {
                    return obj to false
                }
            }
            val newEntry = JsonObject().apply {
                if (matcherValue != null) {
                    addProperty("matcher", matcherValue)
                }
                add("hooks", JsonArray())
            }
            eventHooks.add(newEntry)
            return newEntry to true
        }

        private fun ensureNestedCommandHook(hookArray: JsonArray, command: String): Boolean {
            val legacyCommands = toLegacyScriptCommands(command)
            hookArray.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                if (obj.get("type")?.asString != "command") {
                    return@forEach
                }
                val existingCommand = obj.get("command")?.asString ?: return@forEach
                if (existingCommand == command) {
                    return false
                }
                if (existingCommand in legacyCommands) {
                    obj.addProperty("command", command)
                    return true
                }
            }
            val commandObj = JsonObject().apply {
                addProperty("type", "command")
                addProperty("command", command)
            }
            hookArray.add(commandObj)
            return true
        }
    }

    /**
     * JSON adapter for flat event hooks like:
     * hooks -> eventName -> [{ command, matcher? }]
     */
    private class FlatEventHooksAdapter(
        private val startCommand: String,
        private val stopCommand: String,
        private val startEventName: String,
        private val stopEventName: String,
        private val startMatcher: String?,
        private val stopMatcher: String?,
    ) : HookConfigAdapter {

        override fun merge(existingContent: String?): HookMergeResult {
            val root = parseRootObject(existingContent)
            var changed = false

            val hooks = root.ensureObject("hooks").also { changed = changed || it.second }.first
            val startEvent = hooks.ensureArray(startEventName).also { changed = changed || it.second }.first
            val startCommandAdded = ensureFlatCommandEntry(startEvent, startCommand, startMatcher)
            changed = changed || startCommandAdded

            val stopEvent = hooks.ensureArray(stopEventName).also { changed = changed || it.second }.first
            val stopCommandAdded = ensureFlatCommandEntry(stopEvent, stopCommand, stopMatcher)
            changed = changed || stopCommandAdded

            return HookMergeResult(gson.toJson(root), changed)
        }

        private fun ensureFlatCommandEntry(eventHooks: JsonArray, command: String, matcher: String?): Boolean {
            val legacyCommands = toLegacyScriptCommands(command)
            eventHooks.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                val existingCommand = obj.get("command")?.asString ?: return@forEach
                if (existingCommand != command && existingCommand !in legacyCommands) {
                    return@forEach
                }
                if (matcher == null || obj.get("matcher")?.asString == matcher) {
                    if (existingCommand in legacyCommands) {
                        obj.addProperty("command", command)
                        return true
                    }
                    return false
                }
            }
            val entry = JsonObject().apply {
                addProperty("command", command)
                if (matcher != null) {
                    addProperty("matcher", matcher)
                }
            }
            eventHooks.add(entry)
            return true
        }
    }

    private fun parseRootObject(existingContent: String?): JsonObject {
        if (existingContent.isNullOrBlank()) {
            return JsonObject()
        }
        val element = JsonParser.parseString(existingContent)
        if (!element.isJsonObject) {
            throw IOException("settings_json_root_must_be_object")
        }
        return element.asJsonObject
    }

    private fun JsonObject.ensureObject(key: String): Pair<JsonObject, Boolean> {
        val current = get(key)
        if (current == null || current.isJsonNull) {
            return JsonObject().also { add(key, it) } to true
        }
        if (!current.isJsonObject) {
            throw IOException("${key}_must_be_object")
        }
        return current.asJsonObject to false
    }

    private fun JsonObject.ensureArray(key: String): Pair<JsonArray, Boolean> {
        val current = get(key)
        if (current == null || current.isJsonNull) {
            return JsonArray().also { add(key, it) } to true
        }
        if (!current.isJsonArray) {
            throw IOException("${key}_must_be_array")
        }
        return current.asJsonArray to false
    }

    private fun toLegacyScriptCommands(command: String): Set<String> {
        val pythonCommand = command.substringBefore(" $CLIENT_OPTION ")
        val bareScriptCommand = pythonCommand.removePrefix(PYTHON3_PREFIX).trim()
        return setOf(pythonCommand, bareScriptCommand)
    }
}

data class HookInstallResult(
    val path: String,
    val status: String,
    val reason: String?,
)

data class HookInstallSummary(
    val results: List<HookInstallResult>,
) {
    val isAllSuccess: Boolean
        get() = results.isNotEmpty() && results.all { it.status == "ok" || it.status == "already_installed" || it.status == "skip" }

    fun toDisplayText(): String {
        if (results.isEmpty()) {
            return ""
        }
        return results.joinToString("\n") { result ->
            val reasonText = if (result.reason.isNullOrBlank()) "" else " | reason=${result.reason}"
            "hook=${result.path} status=${result.status}$reasonText"
        }
    }
}
