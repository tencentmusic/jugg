package com.sickworm.intellij.jugg.ide.bean

import com.google.gson.*
import com.sickworm.intellij.jugg.project.runtime.SettingsChangedException
import com.sickworm.intellij.jugg.server.JuggServerChooser
import com.sickworm.intellij.jugg.logger.JuggLogger

/** A confirmation contract rendered by both IDE dialogs and command errors. */
data class SettingConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String = "OK",
    val cancelLabel: String = "Cancel",
) {
    fun html(): String = "<html>" + message.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\n", "<br>") + "</html>"
}

class SettingConfirmationRequired(val key: String, val confirmation: SettingConfirmation) :
    IllegalArgumentException(confirmation.message)

/** Applies validated user edits atomically; never performs UI or network operations under the settings lock. */
class JuggSettingsEditor(
    private val serverChooser: JuggServerChooser = JuggServerChooser(JuggLogger.getGlobalLogger("Settings")),
) {
    fun list(): Map<String, Any?> {
        val snapshot = JuggSettings.snapshot()
        return mapOf("items" to JuggSettingDefinition.all.map { definition ->
            mapOf("key" to definition.key, "value" to definition.display(definition.value(snapshot)), "description" to definition.description)
        })
    }

    fun get(key: String): Map<String, Any?> {
        val definition = JuggSettingDefinition.find(key)
        return mapOf("key" to key, "value" to definition.display(definition.value(JuggSettings.snapshot())))
    }

    fun set(key: String, raw: String, confirm: Boolean = false): Map<String, Any?> =
        edit(JuggSettingDefinition.find(key), raw, confirm)

    fun reset(key: String, confirm: Boolean = false): Map<String, Any?> =
        edit(JuggSettingDefinition.find(key), null, confirm)

    /** The IDE alone renders confirmation UI; remote callers must supply explicit confirmation. */
    fun setFromUi(key: String, raw: String): Boolean {
        try {
            set(key, raw)
        } catch (e: SettingConfirmationRequired) {
            val prompt = e.confirmation
            if (!com.sickworm.intellij.jugg.platform.PlatformApi.showDialog(prompt.title, prompt.html(),
                    okButtonText = prompt.confirmLabel, cancelButtonText = prompt.cancelLabel)) return false
            set(key, raw, confirm = true)
        }
        return true
    }

    private fun edit(definition: JuggSettingDefinition, raw: String?, confirm: Boolean): Map<String, Any?> {
        require(definition.writable) { "Read-only setting: ${definition.key}" }
        repeat(2) { attempt ->
            val snapshot = JuggSettings.snapshot()
            val previous = definition.value(snapshot)
            val value = raw?.let(definition::parse) ?: definition.defaultValue()
            confirmation(definition.key, previous, value, snapshot)?.let {
                if (!confirm) throw SettingConfirmationRequired(definition.key, it)
            }
            val updates = updates(definition, value, snapshot)
            val next = snapshot.deepCopy().also { copy -> updates.forEach { (key, field) -> copy.add(key, field) } }
            val appliedValue = definition.value(next)
            val changed = previous != appliedValue || updates.any { (key, field) ->
                key != definition.storageKey && (snapshot[key] ?: JuggSettings.defaultValue(key)) != field
            }
            if (changed) addEffects(definition.key, updates, snapshot)
            if (updates.any { (key, field) -> snapshot[key] != field }) {
                try { JuggSettings.commit(updates, snapshot) } catch (e: SettingsChangedException) {
                    if (attempt == 0) return@repeat else throw e
                }
            }
            return linkedMapOf<String, Any?>("key" to definition.key, "previousValue" to definition.display(previous),
                "value" to definition.display(appliedValue), "changed" to changed).also { result ->
                if (changed && definition.key in EFFECT_KEYS) result["effects"] = "Applied by each project before its next operation."
            }
        }
        error("Settings changed concurrently.")
    }

    private fun updates(definition: JuggSettingDefinition, value: JsonElement, snapshot: JsonObject): MutableMap<String, JsonElement> {
        val stored = if (definition.key == "defaultCompileSettings") JsonPrimitive(value.toString()) else value
        val updates = mutableMapOf(definition.storageKey to stored)
        if (definition.key == "serverUrl") {
            if (value.isJsonNull) updates.putAll(serverChooser.automaticSettings(snapshot))
            else updates["serverExpireTimeMill"] = JsonPrimitive(-1L)
        }
        if (definition.key == "serverExpireTimeMill" && value.asLong == -1L) {
            require(!snapshot["serverUrl"].let { it == null || it.isJsonNull || it.asString.isBlank() }) { "A custom server URL is required." }
        }
        return updates
    }

    private fun addEffects(key: String, updates: MutableMap<String, JsonElement>, snapshot: JsonObject) {
        val revision = when (key) {
            "isEnableBackupClasspath" -> "backupClasspathRevision"
            "deviceCompatRecordJson" -> "deviceCompatRevision"
            else -> return
        }
        updates[revision] = JsonPrimitive(Math.addExact(snapshot[revision]?.asLong ?: 0L, 1L))
    }

    private fun confirmation(key: String, previous: JsonElement, value: JsonElement, snapshot: JsonObject): SettingConfirmation? = when {
        key == "isEnableBackupClasspath" -> SettingConfirmation("Confirm Switch Backup Classpath", "This will effects compilation stability. Continue?")
        key == "isEmbeddedToApk" && !previous.asBoolean && value.asBoolean -> SettingConfirmation("Enable Embedded to APK",
            "This will embed incremental changes to APK, let incremental effects for Android RemoteViews, but it will cost more time to deploy.\n\nAre you sure to enable?")
        key == "serverUrl" && !value.isJsonNull -> serverConfirmation(value.asString)
        key == "serverExpireTimeMill" && value.asLong == -1L -> {
            val url = snapshot["serverUrl"]
            require(url != null && !url.isJsonNull && url.asString.isNotBlank()) { "A custom server URL is required." }
            serverConfirmation(url.asString)
        }
        else -> null
    }

    private fun serverConfirmation(url: String) = SettingConfirmation("Trust Custom Server?",
        "Jugg will connect to:\n$url\n\nThis server can check for Jugg updates, download and install Jugg update JARs, " +
                "and download and load custom compiler JARs. Continue only if you control or trust it.", "Trust Server")

    companion object {
        private val EFFECT_KEYS = setOf("isEnableBackupClasspath", "deviceCompatRecordJson")
    }
}
