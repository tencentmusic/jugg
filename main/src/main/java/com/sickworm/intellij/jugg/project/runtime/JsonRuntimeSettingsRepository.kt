package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.internal.Streams
import java.io.StringReader
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores shared settings atomically without replacing unrelated fields or hiding user-edit failures. */
class JsonRuntimeSettingsRepository(
    private val settingsFile: File,
    private val globalRootDir: File,
    private val logger: Logger,
) {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    fun load(): JsonObject = try {
        loadStrict()
    } catch (exception: Exception) {
        logger.warn("Load runtime settings JSON failed", exception)
        JsonObject()
    }

    fun loadStrict(): JsonObject {
        if (!settingsFile.exists()) return JsonObject()
        return parseSettingsJson(settingsFile.readText()).asJsonObject
    }

    fun mergeMissing(values: Map<String, JsonElement>): Boolean = try {
        withGlobalResourceLock("Migrate runtime settings", globalRootDir) {
            val json = loadStrict()
            val missing = values.filterKeys { !json.has(it) }
            if (missing.isNotEmpty()) {
                missing.forEach { (key, value) -> json.add(key, value) }
                writeJsonObject(json)
            }
        }
        true
    } catch (exception: Exception) {
        logger.warn("Migrate runtime settings failed", exception)
        false
    }

    fun update(name: String, value: JsonElement): Boolean = try {
        updateStrict(mapOf(name to value))
        true
    } catch (exception: Exception) {
        logger.warn("Update runtime setting failed", exception)
        false
    }

    /** Publishes related fields together; an expected snapshot prevents stale user decisions from overwriting newer state. */
    fun updateStrict(values: Map<String, JsonElement>, expected: JsonObject? = null): JsonObject {
        return withGlobalResourceLock("Update runtime settings", globalRootDir) {
            val json = loadStrict()
            if (expected != null && json != expected) throw SettingsChangedException()
            if (values.any { (key, value) -> json.get(key) != value }) {
                values.forEach { (key, value) -> json.add(key, value) }
                writeJsonObject(json)
            }
            json
        }
    }

    private fun writeJsonObject(json: JsonObject) {
        Files.createDirectories(settingsFile.parentFile.toPath())
        val tempFile = Files.createTempFile(settingsFile.parentFile.toPath(), "settings-", ".tmp")
        try {
            Files.writeString(tempFile, gson.toJson(json))
            try {
                Files.move(tempFile, settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}

/** Signals a recoverable concurrent edit; callers must reload and revalidate before one bounded retry. */
class SettingsChangedException : IllegalStateException("Settings changed concurrently; read the latest values and retry.")

/** Gson's convenience parser enables lenient JSON; settings input must reject malformed JSON. */
internal fun parseSettingsJson(raw: String): JsonElement = JsonReader(StringReader(raw)).use { reader ->
    reader.isLenient = false
    val value = Streams.parse(reader)
    require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing settings data." }
    value
}
