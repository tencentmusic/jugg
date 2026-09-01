package com.sickworm.intellij.jugg.ai.skills

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.skills.agents.AgentHookConfigStyle
import com.sickworm.intellij.jugg.ai.skills.agents.AgentHookTarget
import com.sickworm.intellij.jugg.ai.skills.agents.InstallAgents
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
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
    private const val MATCHER_ALL = "*"
    private const val CLIENT_OPTION = "--client"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Installs hook configuration for Claude. */
    fun installForClaude(
        userHome: File = File(System.getProperty("user.home")),
        logger: Logger,
        pythonCommand: String,
    ): HookInstallSummary {
        return installForClients(setOf(InstallClient.CLAUDE), userHome, logger, pythonCommand)
    }

    /**
     * Installs hook config for selected clients.
     * If no client is selected, no hook config is injected.
     */
    fun installForClients(
        clients: Set<InstallClient>,
        userHome: File = File(System.getProperty("user.home")),
        logger: Logger,
        pythonCommand: String,
    ): HookInstallSummary {
        val startScriptPath = File(JuggGlobalPathManager.hooksDir(userHome), "start.py").absolutePath
        val stopScriptPath = File(JuggGlobalPathManager.hooksDir(userHome), "stop.py").absolutePath
        val editScriptPath = File(JuggGlobalPathManager.hooksDir(userHome), "edit.py").absolutePath
        val commandScriptPath = File(JuggGlobalPathManager.hooksDir(userHome), "command.py").absolutePath
        val targets = resolveTargets(clients, userHome, startScriptPath, stopScriptPath,
            editScriptPath, commandScriptPath, pythonCommand)
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
        editScriptPath: String,
        commandScriptPath: String,
        pythonCommand: String,
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
                                startCommand = buildHookCommand(pythonCommand, startScriptPath, target.clientArgument),
                                stopCommand = buildHookCommand(pythonCommand, stopScriptPath, target.clientArgument),
                                editCommand = buildHookCommand(pythonCommand, editScriptPath, target.clientArgument),
                                commandCommand = buildHookCommand(pythonCommand, commandScriptPath, target.clientArgument),
                            ),
                        )
                    }
            }
    }

    private fun buildHookCommand(pythonCommand: String, scriptPath: String, clientArgument: String): String {
        val normalizedScriptPath = scriptPath.replace('\\', '/')
        return "$pythonCommand \"$normalizedScriptPath\" $CLIENT_OPTION $clientArgument"
    }

    private fun buildAdapter(
        target: AgentHookTarget,
        startCommand: String,
        stopCommand: String,
        editCommand: String,
        commandCommand: String,
    ): HookConfigAdapter {
        return when (target.style) {
            AgentHookConfigStyle.NESTED_EVENT_HOOKS -> {
                NestedEventHooksAdapter(
                    startCommand = startCommand,
                    stopCommand = stopCommand,
                    editCommand = editCommand,
                    commandCommand = commandCommand,
                    startEventName = target.startEventName,
                    stopEventName = target.stopEventName,
                    editEventName = target.editEventName,
                    commandEventName = target.commandEventName,
                    startMatcher = null,
                    stopMatcher = target.stopMatcher ?: MATCHER_ALL,
                    editMatcher = target.editMatcher,
                    commandMatcher = target.commandMatcher,
                )
            }

            AgentHookConfigStyle.FLAT_EVENT_COMMANDS -> {
                FlatEventHooksAdapter(
                    startCommand = startCommand,
                    stopCommand = stopCommand,
                    editCommand = editCommand,
                    commandCommand = commandCommand,
                    startEventName = target.startEventName,
                    stopEventName = target.stopEventName,
                    editEventName = target.editEventName,
                    commandEventName = target.commandEventName,
                    startMatcher = MATCHER_ALL,
                    stopMatcher = MATCHER_ALL,
                    editMatcher = target.editMatcher,
                    commandMatcher = target.commandMatcher,
                    configVersion = target.configVersion,
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
        writeAtomically(target.file, mergeResult.mergedContent)
        return HookInstallResult(path = target.file.path, status = "ok", reason = null)
    }

    private fun writeAtomically(targetFile: File, content: String) {
        val parent = targetFile.parentFile ?: throw IOException("missing_parent_dir")
        parent.mkdirs()

        val tempFile = File(parent, "${targetFile.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
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
        private val editCommand: String,
        private val commandCommand: String,
        private val startEventName: String,
        private val stopEventName: String,
        private val editEventName: String?,
        private val commandEventName: String?,
        private val startMatcher: String?,
        private val stopMatcher: String?,
        private val editMatcher: String?,
        private val commandMatcher: String?,
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
            val editCommandAdded = ensureOptionalNestedCommand(hooks, editEventName, editMatcher, editCommand)
            val commandCommandAdded = ensureOptionalNestedCommand(hooks, commandEventName, commandMatcher, commandCommand)
            changed = changed || editCommandAdded || commandCommandAdded

            return HookMergeResult(gson.toJson(root), changed)
        }

        private fun ensureOptionalNestedCommand(
            hooks: JsonObject,
            eventName: String?,
            matcher: String?,
            command: String,
        ): Boolean {
            if (eventName.isNullOrBlank()) {
                return false
            }
            var changed = false
            val event = hooks.ensureArray(eventName).also { changed = changed || it.second }.first
            changed = removeLegacyWildcardNestedCommand(event, matcher) || changed
            val entry = findOrCreateMatcherEntry(event, matcher).also { changed = changed || it.second }.first
            val hookArray = entry.ensureArray("hooks").also { changed = changed || it.second }.first
            val commandAdded = ensureNestedCommandHook(hookArray, command)
            return changed || commandAdded
        }

        private fun removeLegacyWildcardNestedCommand(
            eventHooks: JsonArray,
            matcher: String?,
        ): Boolean {
            if (matcher.isNullOrBlank() || matcher == MATCHER_ALL) {
                return false
            }
            var changed = false
            eventHooks.forEach { element ->
                if (!element.isJsonObject) {
                    return@forEach
                }
                val item = element.asJsonObject
                val itemMatcher = item.get("matcher")?.asString
                if (!(itemMatcher == null || itemMatcher == MATCHER_ALL)) {
                    return@forEach
                }
                val hookArray = item.getAsJsonArray("hooks") ?: return@forEach
                var index = hookArray.size() - 1
                while (index >= 0) {
                    val hook = hookArray[index]
                    if (!hook.isJsonObject) {
                        index--
                        continue
                    }
                    val hookObject = hook.asJsonObject
                    val commandValue = hookObject.get("command")?.asString
                    if (hookObject.get("type")?.asString == "command" && commandValue.isManagedJuggCommand()) {
                        hookArray.remove(index)
                        changed = true
                    }
                    index--
                }
            }
            return changed
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
            var foundManagedCommand = false
            var changed = false
            var index = 0
            while (index < hookArray.size()) {
                val element = hookArray[index]
                if (!element.isJsonObject) {
                    index++
                    continue
                }
                val obj = element.asJsonObject
                if (obj.get("type")?.asString != "command") {
                    index++
                    continue
                }
                val existingCommand = obj.get("command")?.asString
                if (!existingCommand.isManagedJuggCommand()) {
                    index++
                    continue
                }
                if (!foundManagedCommand) {
                    foundManagedCommand = true
                    if (existingCommand != command) {
                        obj.addProperty("command", command)
                        changed = true
                    }
                    index++
                    continue
                }
                hookArray.remove(index)
                changed = true
            }
            if (foundManagedCommand) {
                return changed
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
        private val editCommand: String,
        private val commandCommand: String,
        private val startEventName: String,
        private val stopEventName: String,
        private val editEventName: String?,
        private val commandEventName: String?,
        private val startMatcher: String?,
        private val stopMatcher: String?,
        private val editMatcher: String?,
        private val commandMatcher: String?,
        private val configVersion: Int?,
    ) : HookConfigAdapter {

        override fun merge(existingContent: String?): HookMergeResult {
            val root = parseRootObject(existingContent)
            var changed = false

            if (configVersion != null && existingContent.isNullOrBlank()) {
                root.addProperty("version", configVersion)
                changed = true
            }

            val hooks = root.ensureObject("hooks").also { changed = changed || it.second }.first
            val startEvent = hooks.ensureArray(startEventName).also { changed = changed || it.second }.first
            val startCommandAdded = ensureFlatCommandEntry(startEvent, startCommand, startMatcher)
            changed = changed || startCommandAdded

            val stopEvent = hooks.ensureArray(stopEventName).also { changed = changed || it.second }.first
            val stopCommandAdded = ensureFlatCommandEntry(stopEvent, stopCommand, stopMatcher)
            changed = changed || stopCommandAdded
            val editCommandAdded = ensureOptionalFlatCommand(hooks, editEventName, editMatcher, editCommand)
            val commandCommandAdded = ensureOptionalFlatCommand(hooks, commandEventName, commandMatcher, commandCommand)
            changed = changed || editCommandAdded || commandCommandAdded

            return HookMergeResult(gson.toJson(root), changed)
        }

        private fun ensureOptionalFlatCommand(
            hooks: JsonObject,
            eventName: String?,
            matcher: String?,
            command: String,
        ): Boolean {
            if (eventName.isNullOrBlank()) {
                return false
            }
            var changed = false
            val event = hooks.ensureArray(eventName).also { changed = changed || it.second }.first
            changed = removeLegacyWildcardFlatCommand(event, matcher) || changed
            val commandAdded = ensureFlatCommandEntry(event, command, matcher)
            return changed || commandAdded
        }

        private fun removeLegacyWildcardFlatCommand(
            eventHooks: JsonArray,
            matcher: String?,
        ): Boolean {
            if (matcher.isNullOrBlank() || matcher == MATCHER_ALL) {
                return false
            }
            var changed = false
            var index = eventHooks.size() - 1
            while (index >= 0) {
                val item = eventHooks[index]
                if (!item.isJsonObject) {
                    index--
                    continue
                }
                val itemObject = item.asJsonObject
                val itemMatcher = itemObject.get("matcher")?.asString
                val commandValue = itemObject.get("command")?.asString
                val isLegacyMatcher = itemMatcher == null || itemMatcher == MATCHER_ALL
                if (isLegacyMatcher && commandValue.isManagedJuggCommand()) {
                    eventHooks.remove(index)
                    changed = true
                }
                index--
            }
            return changed
        }

        private fun ensureFlatCommandEntry(eventHooks: JsonArray, command: String, matcher: String?): Boolean {
            var foundManagedCommand = false
            var changed = false
            var index = 0
            while (index < eventHooks.size()) {
                val element = eventHooks[index]
                if (!element.isJsonObject) {
                    index++
                    continue
                }
                val obj = element.asJsonObject
                val existingCommand = obj.get("command")?.asString
                if (!existingCommand.isManagedJuggCommand()) {
                    index++
                    continue
                }
                if (matcher != null && obj.get("matcher")?.asString != matcher) {
                    index++
                    continue
                }
                if (!foundManagedCommand) {
                    foundManagedCommand = true
                    if (existingCommand != command) {
                        obj.addProperty("command", command)
                        changed = true
                    }
                    index++
                    continue
                }
                eventHooks.remove(index)
                changed = true
            }
            if (foundManagedCommand) {
                return changed
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

    private fun String?.isManagedJuggCommand(): Boolean {
        return this != null && contains(".jugg")
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
