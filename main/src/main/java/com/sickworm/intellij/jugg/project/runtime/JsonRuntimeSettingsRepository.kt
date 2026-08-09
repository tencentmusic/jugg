package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores raw runtime-setting fields in the cross-runtime `~/.jugg/settings.json` file. */
class JsonRuntimeSettingsRepository(
    private val settingsFile: File,
    private val globalRootDir: File,
    private val logger: Logger,
) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): JsonObject {
        return try {
            readJsonObject()
        } catch (exception: Exception) {
            logger.warn("Load runtime settings JSON failed", exception)
            JsonObject()
        }
    }

    fun mergeMissing(values: Map<String, JsonElement>): Boolean {
        if (values.isEmpty()) return true
        return try {
            withGlobalResourceLock("Migrate runtime settings", globalRootDir) {
                val json = readJsonObject()
                var changed = false
                values.forEach { (name, value) ->
                    if (!json.has(name)) {
                        json.add(name, value)
                        changed = true
                    }
                }
                if (changed) writeJsonObject(json)
            }
            true
        } catch (exception: Exception) {
            logger.warn("Migrate runtime settings failed", exception)
            false
        }
    }

    fun update(name: String, value: JsonElement): Boolean {
        return updateSafely("Update runtime setting") { json ->
            if (json.get(name) == value) return@updateSafely false
            json.add(name, value)
            true
        }
    }

    private fun updateSafely(taskName: String, update: (JsonObject) -> Boolean): Boolean {
        return try {
            withGlobalResourceLock(taskName, globalRootDir) {
                val json = readJsonObject()
                if (!update(json)) return@withGlobalResourceLock false
                writeJsonObject(json)
                true
            }
        } catch (exception: Exception) {
            logger.warn("$taskName failed", exception)
            false
        }
    }

    private fun readJsonObject(): JsonObject {
        if (!settingsFile.exists()) return JsonObject()
        return JsonParser.parseString(settingsFile.readText()).asJsonObject
    }

    private fun writeJsonObject(json: JsonObject) {
        settingsFile.parentFile?.mkdirs()
        val tempFile = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
        tempFile.writeText(gson.toJson(json))
        try {
            Files.move(tempFile.toPath(), settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tempFile.delete()
        }
    }
}
