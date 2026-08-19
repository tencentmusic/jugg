package com.sickworm.intellij.jugg.ai.skills

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import java.io.File
import java.nio.file.Files

/** Exports Jugg-managed Claude hooks as a CC Switch Common Config snippet. */
object CcSwitchCommonConfigGuideExporter {
    private const val CONFIG_DIR_NAME = ".cc-switch"
    private const val CONFIG_DIR_ENV = "CC_SWITCH_CONFIG_DIR"
    private const val GUIDE_FILE_NAME = "claude-hooks-common-config.json"
    private const val JUGG_HOOK_PATH = ".jugg/skills/hooks/"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun isConfigDirectoryDetected(userHome: File = File(System.getProperty("user.home"))): Boolean {
        return configDirectories(userHome).any { it.isDirectory }
    }

    fun exportClaudeHooks(userHome: File = File(System.getProperty("user.home"))): File {
        val settingsFile = File(userHome, ".claude/settings.json")
        val root = JsonParser.parseString(settingsFile.readText()).asJsonObject
        val hooks = root.getAsJsonObject("hooks") ?: throw IllegalStateException("Claude hooks not found")
        val copiedHooks = copyJuggHooks(hooks, userHome)
        val commonConfig = JsonObject().apply { add("hooks", copiedHooks) }
        val outputFile = File(JuggGlobalPathManager.ccSwitchDir(userHome), GUIDE_FILE_NAME)
        outputFile.parentFile.mkdirs()
        Files.writeString(outputFile.toPath(), gson.toJson(commonConfig))
        return outputFile
    }

    private fun configDirectories(userHome: File): List<File> {
        val directories = linkedSetOf(File(userHome, CONFIG_DIR_NAME))
        System.getenv(CONFIG_DIR_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { directories.add(File(it)) }
        return directories.toList()
    }

    private fun copyJuggHooks(hooks: JsonObject, userHome: File): JsonObject {
        val copiedHooks = JsonObject()
        hooks.entrySet().forEach { (eventName, eventValue) ->
            val copiedEntries = JsonArray()
            eventValue.asJsonArray.forEach entryLoop@{ entry ->
                val copiedCommands = entry.asJsonObject.getAsJsonArray("hooks")
                    .filter { hook -> isJuggHook(hook.asJsonObject, userHome) }
                if (copiedCommands.isEmpty()) {
                    return@entryLoop
                }
                val copiedEntry = entry.asJsonObject.deepCopy()
                copiedEntry.add("hooks", JsonArray().apply { copiedCommands.forEach { add(it.deepCopy()) } })
                copiedEntries.add(copiedEntry)
            }
            if (copiedEntries.size() > 0) {
                copiedHooks.add(eventName, copiedEntries)
            }
        }
        check(copiedHooks.size() > 0) { "Jugg Claude hooks not found" }
        return copiedHooks
    }

    private fun isJuggHook(hook: JsonObject, userHome: File): Boolean {
        val command = hook.get("command")?.asString?.replace('\\', '/') ?: return false
        if (hook.get("type")?.asString != "command") {
            return false
        }
        val hooksPath = JuggGlobalPathManager.hooksDir(userHome).absolutePath.replace('\\', '/')
        return command.contains(hooksPath) || command.contains(JUGG_HOOK_PATH)
    }
}
