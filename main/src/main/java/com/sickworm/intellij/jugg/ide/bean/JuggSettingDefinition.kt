package com.sickworm.intellij.jugg.ide.bean

import com.google.gson.*
import com.sickworm.intellij.jugg.project.runtime.parseSettingsJson
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty0
import kotlin.reflect.full.memberProperties

/** Shared metadata and typed values for settings exposed by IDE, MCP, and CLI. */
class JuggSettingDefinition(
    val property: KProperty0<*>,
    val label: String,
    val description: String,
    val writable: Boolean = property is KMutableProperty0<*>,
) {
    val key get() = property.name
    val expectedType get() = property.returnType.toString()
    internal val storageKey get() = if (key == "defaultCompileSettings") "defaultCompileSettingsJson" else key
    private val type get() = property.returnType.classifier
    private val gson = Gson()

    fun defaultValue(): JsonElement = when {
        key == "defaultCompileSettings" -> gson.toJsonTree(RunConfigurationTemplate.default)
        writable -> requireNotNull(JuggSettings.defaultValue(key))
        else -> gson.toJsonTree(property.get())
    }

    fun value(snapshot: JsonObject): JsonElement {
        if (!writable) return gson.toJsonTree(property.get())
        val stored = snapshot[storageKey] ?: return defaultValue()
        if (key == "defaultCompileSettings") {
            require(stored.isJsonPrimitive && stored.asJsonPrimitive.isString) { "Invalid stored setting: $key" }
            return if (stored.asString.isEmpty()) defaultValue() else parse(stored.asString)
        }
        return validate(stored.deepCopy())
    }

    fun parse(raw: String): JsonElement = try {
        val value = when (type) {
            Boolean::class -> { require(raw == "true" || raw == "false"); JsonPrimitive(raw.toBooleanStrict()) }
            Int::class -> JsonPrimitive(raw.toInt())
            Long::class -> JsonPrimitive(raw.toLong())
            String::class -> if (key == "serverUrl" && (raw.isBlank() || raw == "null")) JsonNull.INSTANCE else JsonPrimitive(raw)
            RunConfigurationTemplate::class -> parseSettingsJson(raw)
            else -> error("Unsupported setting type: $key")
        }
        validate(value)
    } catch (e: InvalidSettingValue) {
        throw e
    } catch (_: Exception) {
        throw InvalidSettingValue(key, expectedType, key)
    }

    private fun validate(value: JsonElement): JsonElement {
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        when (type) {
            Boolean::class -> require(primitive?.isBoolean == true)
            Int::class -> require(primitive?.isNumber == true && primitive.asString.toIntOrNull() != null)
            Long::class -> require(primitive?.isNumber == true && primitive.asString.toLongOrNull() != null)
            String::class -> require(primitive?.isString == true || (key == "serverUrl" && value.isJsonNull))
            RunConfigurationTemplate::class -> validateTemplate(value)
        }
        when (key) {
            "maxCompileSourceModules", "maxCompileSourceFilePoints" -> require(value.asInt > 0)
            "serverExpireTimeMill" -> require(value.asLong >= -1)
            "serverUrl" -> if (!value.isJsonNull) {
                val uri = java.net.URI(value.asString.trim())
                require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
                return JsonPrimitive(value.asString.trim())
            }
            "deviceCompatRecordJson", "sliceDeployRecordJson" -> validateRecords(value.asString)
        }
        return value
    }

    private fun validateRecords(raw: String) {
        if (raw.isEmpty()) return
        val records = parseSettingsJson(raw).asJsonArray
        val fields = if (key == "deviceCompatRecordJson") setOf("displayName", "applications")
            else setOf("displayName", "firstSliceSize", "sliceSize")
        records.forEachIndexed { index, record ->
            val path = "$key[$index]"
            if (!record.isJsonObject) throw InvalidSettingValue(key, "record object", path)
            record.asJsonObject.entrySet().forEach { (name, field) ->
                if (name !in fields) throw InvalidSettingValue(key, "known record field", "$path.$name")
                validateRecordField(name, field, "$path.$name")
            }
        }
    }

    private fun validateRecordField(name: String, field: JsonElement, path: String) {
        if (field.isJsonNull) return
        val valid = when (name) {
            "applications" -> field.isJsonArray && field.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            "displayName" -> field.isJsonPrimitive && field.asJsonPrimitive.isString
            else -> field.isJsonPrimitive && field.asJsonPrimitive.isNumber && (field.asString.toIntOrNull() ?: 0) > 0
        }
        if (!valid) throw InvalidSettingValue(key, if (name in setOf("firstSliceSize", "sliceSize")) "positive Int" else name, path)
    }

    private fun validateTemplate(value: JsonElement) {
        require(value.isJsonObject)
        val defaults = gson.toJsonTree(RunConfigurationTemplate.default).asJsonObject
        val properties = RunConfigurationTemplate::class.memberProperties.associateBy { it.name }
        value.asJsonObject.entrySet().forEach { (name, field) ->
            val path = "$key.$name"
            val property = properties[name] ?: throw InvalidSettingValue(key, expectedType, path)
            val valid = if (field.isJsonNull) property.returnType.isMarkedNullable else when (property.returnType.classifier) {
                Boolean::class -> field.isJsonPrimitive && field.asJsonPrimitive.isBoolean
                Int::class -> field.isJsonPrimitive && field.asJsonPrimitive.isNumber && field.asString.toIntOrNull() != null
                String::class -> field.isJsonPrimitive && field.asJsonPrimitive.isString
                else -> false
            }
            if (!valid) throw InvalidSettingValue(key, property.returnType.toString(), path)
            if (name in SECRET_FIELDS && !field.isJsonNull && field.asString == REDACTED) {
                throw InvalidSettingValue(key, "an unmasked value", path)
            }
        }
        // Partial templates inherit the same defaults as a new IDE run configuration.
        defaults.entrySet().forEach { (name, field) -> if (!value.asJsonObject.has(name)) value.asJsonObject.add(name, field) }
        properties.values.filterNot { it.returnType.isMarkedNullable }.forEach { require(value.asJsonObject.has(it.name)) }
        for (name in listOf("remoteSshPort", "httpProxyPort")) {
            if (value.asJsonObject[name].asInt !in 0..65535) throw InvalidSettingValue(key, "port 0..65535", "$key.$name")
        }
        val mode = value.asJsonObject["syncMode"]
        if (mode != null && !mode.isJsonNull && SyncMode.values().none { it.modeName == mode.asString }) {
            throw InvalidSettingValue(key, "sync mode", "$key.syncMode")
        }
    }

    fun display(value: JsonElement): JsonElement {
        if (key != "defaultCompileSettings") return value.deepCopy()
        return value.deepCopy().also { copy ->
            SECRET_FIELDS.forEach { name ->
                val secret = copy.asJsonObject[name]
                if (secret != null && !secret.isJsonNull && secret.asString.isNotEmpty()) copy.asJsonObject.addProperty(name, REDACTED)
            }
        }
    }

    companion object {
        const val REDACTED = "<redacted>"
        private val SECRET_FIELDS = setOf("remoteSshPassword", "environmentVariables")
        val all: List<JuggSettingDefinition> by lazy { listOf(
            JuggSettingDefinition(JuggSettings::compileOnSave, "Compile on save", "Compile detected file changes in IDEA; standalone does not consume this setting."),
            JuggSettingDefinition(JuggSettings::deployOnSave, "Deploy on save", "Persisted save preference; currently no runtime consumes this setting."),
            JuggSettingDefinition(JuggSettings::isConfirmFallbackWhenNoFileChanges, "Confirm fallback when no files changed", "Ask before running a full Gradle build."),
            JuggSettingDefinition(JuggSettings::isConfirmFallbackWhenTooManyChanges, "Confirm fallback when too many changes", "Ask before running a full Gradle build."),
            JuggSettingDefinition(JuggSettings::isAlwaysRestartAppAfterDeployment, "Always restart app after deployment", "Disables hot reload to ensure global state is reinitialized."),
            JuggSettingDefinition(JuggSettings::isAutoFallbackToGradleWhenDeployError, "Auto fallback after deploy failure", "Recover with a full Gradle build."),
            JuggSettingDefinition(JuggSettings::isEmbeddedToApk, "Embed changes into APK", "Supports Android RemoteViews with a slower deploy."),
            JuggSettingDefinition(JuggSettings::isCheckChecksumWhenFileChanges, "Check file checksums", "Check checksums before treating a file as changed."),
            JuggSettingDefinition(JuggSettings::isEnableDirectOverlayDeploy, "Quick deploy", "Skip app startup when direct overlay is available."),
            JuggSettingDefinition(JuggSettings::isUseProjectKotlinCompiler, "Use project Kotlin compiler", "Matches the compiler configured by the project."),
            JuggSettingDefinition(JuggSettings::isEnableBackupClasspath, "Backup classpath", "May improve recovery at the cost of extra storage."),
            JuggSettingDefinition(JuggSettings::isIgnoreWontCompileModules, "Ignore modules excluded from compilation", "Ignore changes in modules that will not be compiled."),
            JuggSettingDefinition(JuggSettings::maxCompileSourceModules, "Maximum source modules", "Maximum number of source modules before requesting full-build fallback."),
            JuggSettingDefinition(JuggSettings::maxCompileSourceFilePoints, "Maximum source file points", "Maximum source file points before requesting full-build fallback; Kotlin counts 3, Java counts 2."),
            JuggSettingDefinition(JuggSettings::isEnableWarmUp, "Compiler warmup", "Warm up compilers after full compilation."),
            JuggSettingDefinition(JuggSettings::isEnableConstRefTasks, "Constant reference analysis", "Analyze references to constants for incremental compilation."),
            JuggSettingDefinition(JuggSettings::serverUrl, "Custom server", "Server URL for remote compilation, issue reporting, updates, and compiler downloads; empty restores automatic selection."),
            JuggSettingDefinition(JuggSettings::serverExpireTimeMill, "Server expiration", "Server expiration timestamp in milliseconds: -1 for trusted custom server, 0 for automatic reselection."),
            JuggSettingDefinition(JuggSettings::deviceCompatRecordJson, "Device compatibility records", "JSON array of device compatibility deployment records."),
            JuggSettingDefinition(JuggSettings::sliceDeployRecordJson, "Slice deployment records", "JSON array of device deployment slice sizes."),
            JuggSettingDefinition(JuggSettings::defaultCompileSettings, "Default compile settings", "JSON template used when creating run configurations; credentials are redacted in output."),
            JuggSettingDefinition(JuggSettings::isEnableInjectGradleCompile, "isEnableInjectGradleCompile", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::isEnableReadProjectInfoFromGradle, "isEnableReadProjectInfoFromGradle", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::finalIsEnableReadProjectInfoFromGradle, "finalIsEnableReadProjectInfoFromGradle", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::isEnableCompatibleDeploymentMode, "isEnableCompatibleDeploymentMode", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::finalIsEnableCompatibleDeploymentMode, "finalIsEnableCompatibleDeploymentMode", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::minErrorToRecreateCompiler, "minErrorToRecreateCompiler", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::sourceFileSizeToTriggerDetectRollback, "sourceFileSizeToTriggerDetectRollback", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::isQuickFallbackToHotFix, "isQuickFallbackToHotFix", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::overlayDeploySplitSize, "overlayDeploySplitSize", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::overlayDeploySplitSizeFirstSlice, "overlayDeploySplitSizeFirstSlice", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::isForceEnableBackupClasspath, "isForceEnableBackupClasspath", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::finalIsEnableBackupClasspath, "finalIsEnableBackupClasspath", "Read-only runtime capability or effective setting.", writable = false),
            JuggSettingDefinition(JuggSettings::isCanUseBackupClasspath, "isCanUseBackupClasspath", "Read-only runtime capability or effective setting.", writable = false),
        ) }
        fun find(key: String): JuggSettingDefinition = all.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Unknown setting: $key")
    }
}

/** Safe validation details never include the supplied value or credentials. */
class InvalidSettingValue(val key: String, val expectedType: String, val fieldPath: String) :
    IllegalArgumentException("Invalid value at $fieldPath (expected $expectedType).")
