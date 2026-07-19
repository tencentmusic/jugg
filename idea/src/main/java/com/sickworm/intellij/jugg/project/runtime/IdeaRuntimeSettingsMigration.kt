package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.intellij.ide.util.PropertiesComponent
import com.sickworm.intellij.jugg.ide.bean.JuggSettings

internal const val RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY = "jugg.runtimeSettingsJsonMigrationCompleted"

/** Migrates legacy IDEA properties once, retrying on the next startup when JSON persistence fails. */
internal fun JuggSettings.migrateLegacyJuggSettings(propertiesComponent: PropertiesComponent): Boolean {
    if (propertiesComponent.getBoolean(RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY, false)) return true
    val success = migrate(readLegacyJuggSettings(propertiesComponent))
    if (success) propertiesComponent.setValue(RUNTIME_SETTINGS_MIGRATION_COMPLETED_KEY, true, false)
    return success
}

/** Converts explicitly stored IDEA properties into shared JSON setting fields. */
internal fun readLegacyJuggSettings(propertiesComponent: PropertiesComponent): Map<String, JsonElement> {
    val values = linkedMapOf<String, JsonElement>()
    fun read(name: String): String? {
        val key = "jugg.$name"
        if (!propertiesComponent.isValueSet(key)) return null
        return propertiesComponent.getValue(key)
    }
    fun addString(fieldName: String, legacyName: String) {
        read(legacyName)?.let { values[fieldName] = JsonPrimitive(it) }
    }
    fun addBoolean(fieldName: String, legacyName: String) {
        read(legacyName)?.toBooleanStrictOrNull()?.let { values[fieldName] = JsonPrimitive(it) }
    }
    fun addLong(fieldName: String, legacyName: String) {
        read(legacyName)?.toLongOrNull()?.let { values[fieldName] = JsonPrimitive(it) }
    }

    addString("defaultCompileSettingsJson", "defaultCompileSettingsJson")
    addString("deviceCompatRecordJson", "deviceCompatRecordJson")
    addString("sliceDeployRecordJson", "sliceDeployRecordJson")
    addString("serverUrl", "serverUrl")
    addBoolean("compileOnSave", "compileOnSave")
    addBoolean("deployOnSave", "deployOnSave")
    addBoolean("isConfirmFallbackWhenNoFileChanges", "isConfirmFallbackWhenNoFileChanges")
    addBoolean("isAlwaysRestartAppAfterDeployment", "isAlwaysRestartAppAfterDeployment")
    addBoolean("isAutoFallbackToGradleWhenDeployError", "isAutoFallbackToGradleWhenDeployError")
    addBoolean("isEmbeddedToApk", "isEmbeddedToApk")
    addBoolean("isCheckChecksumWhenFileChanges", "isCheckChecksumWhenFileChanges")
    addBoolean("isEnableDirectOverlayDeploy", "isEnableDirectOverlayDeploy")
    addBoolean("isUseProjectKotlinCompiler", "isUseProjectKotlinCompiler_v3")
    addBoolean("isEnableBackupClasspath", "isEnableBackupClasspath_v2")
    addBoolean("isIgnoreWontCompileModules", "isIgnoreWontCompileModules")
    addLong("serverExpireTimeMill", "serverExpireTimeMill")
    return values
}
