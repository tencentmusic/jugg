@file:Suppress("ConstPropertyName", "SimplifyBooleanWithConstants", "KotlinConstantConditions")

package com.sickworm.intellij.jugg.ide.bean

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.runtime.JsonRuntimeSettingsRepository
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import com.sickworm.intellij.jugg.server.typeAdapter
import java.io.File
import kotlin.reflect.KProperty

/**
 * JuggSettings stores persisted and effective settings that control Jugg compile/deploy behavior.
 * Collaboration: Fields are persisted through [JsonRuntimeSettingsRepository], then consumed by IDE/server/compiler/deploy flows.
 * Data Contract: Persisted entries use property names as JSON keys; `final*` getters expose effective switches composed from base flags.
 */
object JuggSettings {

    private const val MAX_REMOTE_COMMAND_HISTORY_SIZE = 10

    private val logger by lazy { JuggLogger.getGlobalLogger("JuggSettings") }
    private var storageCache: Storage? = null

    /** Fills missing JSON fields from a legacy source and refreshes effective values on next access. */
    @Synchronized
    fun migrate(legacyValues: Map<String, JsonElement>): Boolean {
        val success = createRepository(JuggGlobalPathManager.rootDir).mergeMissing(legacyValues)
        storageCache = null
        return success
    }

    /** Discards the process snapshot so the next access reads settings written by another runtime. */
    @Synchronized
    fun reload() {
        storageCache = null
    }

    var compileOnSave by setting(false)
    var deployOnSave by setting(false)

    // default compile settings
    private var defaultCompileSettingsJson by setting("")

    // Run options start

    var isConfirmFallbackWhenNoFileChanges by setting(true)
    var isAlwaysRestartAppAfterDeployment by setting(false)
    var isAutoFallbackToGradleWhenDeployError by setting(false)
    var isEmbeddedToApk by setting(false)

    // Run options end

    /** whether check checksum to make sure file is really change when file changes */
    var isCheckChecksumWhenFileChanges by setting(true)

    /**
     * Enable init gradle scripts when gradle compile.
     * Effected function: [isEnableReadProjectInfoFromGradle], [isEnableCompatibleDeploymentMode]
     */
    const val isEnableInjectGradleCompile: Boolean = true

    /**
     * Enable read project info from gradle.
     */
    const val isEnableReadProjectInfoFromGradle: Boolean = true
    val finalIsEnableReadProjectInfoFromGradle get() = isEnableInjectGradleCompile && isEnableReadProjectInfoFromGradle

    /**
     * compat deploy strategy for:
     * Huawei HarmonyOS 4.2 and above
     * Xiaomi HyperOS
     * Device API lower than 30
     * For Huawei HarmonyOS 4.2 and above, Jugg will automatically use compat deploy.
     * For Xiaomi HyperOS, some apps may get "MISSING_AGENT_RESPONSES", Jugg will use hot fix deployment solution to compat with it.
     * For Device API lower than 30, Jugg will use hot fix deployment solution to compat with it.
     */
    const val isEnableCompatibleDeploymentMode: Boolean = true
    const val finalIsEnableCompatibleDeploymentMode = isEnableInjectGradleCompile && isEnableCompatibleDeploymentMode

    /**
     * Enables direct overlay deploy shortcuts that do not require the app process to be online.
     */
    var isEnableDirectOverlayDeploy by setting(true)
    var isUseProjectKotlinCompiler by setting(true)

    /** limit max source modules to compile for better performance */
    var maxCompileSourceModules = 25

    /**
     * Limit max source files to compile for better performance.
     * Kotlin counts 3 and java counts 2, because I found that Kotlin generate 3 classes for each source file average,
     * and Java generate 2 classes for each source file average.
     */
    var maxCompileSourceFilePoints = 180

    /** limit min compiler error to recreate once */
    const val minErrorToRecreateCompiler = 30

    /** source file size to trigger detect rollback */
    const val sourceFileSizeToTriggerDetectRollback = 20

    /** Whether warm up compiler after init compilation. False for unit test. */
    var isEnableWarmUp: Boolean = true

    /**
     * Whether fallback all to hot fix if there is any hot fix files.
     * Enable this can skip JVM-TI process and save time.
     */
    const val isQuickFallbackToHotFix: Boolean = true

    /**
     * Apply changes may time out("MessagePipeWrapper read() timeout (5000ms)") on first time
     * deploy overlays if size is super huge.
     * For a device made in 2018, deploy 40,000+ overlays need about 4-6s in UpdateOverlay, it's easy to timeout.
     *
     * Here we split multiple deploy task to avoid timeout.
     */
    const val overlayDeploySplitSize = 20_000

    /**
     * First piece size for split deploy task, it will be slower and need smaller size.
     */
    const val overlayDeploySplitSizeFirstSlice = 10_000

    var isEnableBackupClasspath by setting(false)

    /** Process-only override used by CLI compilation without changing shared user settings. */
    var isForceEnableBackupClasspath = false
    val finalIsEnableBackupClasspath get() = isForceEnableBackupClasspath || isEnableBackupClasspath

    // windows not support rsync, so disable backup classpath
    var isCanUseBackupClasspath: Boolean = !isWindows
    var deviceCompatRecordJson by setting("")
    var sliceDeployRecordJson by setting("")
    private var remoteCommandHistoryJson by setting("")
    var isIgnoreWontCompileModules by setting(false)

    /**
     * Master switch for const-ref analysis tasks.
     * When disabled, Jugg will skip const-ref full scan/incremental analyze/readiness wait/effected-source query.
     */
    var isEnableConstRefTasks: Boolean = true

    /**
     * Use this for Jugg run configuration arguments if first set.
     */
    var defaultCompileSettings: RunConfigurationTemplate
        get() {
            var default = RunConfigurationTemplate.default
            if (defaultCompileSettingsJson.isNotEmpty()) {
                // use last compile success settings
                try {
                    default = GsonBuilder()
                        .registerTypeAdapter(String::class.java, RunConfigurationTemplate.typeAdapter)
                        .create()
                        .fromJson(defaultCompileSettingsJson, RunConfigurationTemplate::class.java)
                } catch (_: Exception) {
                    // ignore
                }
            }
            return default
        }
        set(value) {
            defaultCompileSettingsJson = Gson().toJson(value)
        }

    var serverUrl by nullableStringSetting()
    var serverExpireTimeMill by setting(0L)

    /** Returns recent commands for one remote SSH target and project directory. */
    fun getRemoteCommandHistory(targetKey: String): List<String> {
        if (targetKey.isBlank()) return emptyList()
        return readRemoteCommandHistory()[targetKey].orEmpty()
    }

    /** Records one command for a remote target, moving duplicates to the front and keeping ten entries. */
    fun recordRemoteCommand(targetKey: String, command: String) {
        val normalizedCommand = command.trim()
        if (targetKey.isBlank() || normalizedCommand.isEmpty()) return
        try {
            val history = readRemoteCommandHistory().toMutableMap()
            val targetHistory = mutableListOf(normalizedCommand)
            targetHistory.addAll(history[targetKey].orEmpty().filterNot { it == normalizedCommand })
            history[targetKey] = targetHistory.take(MAX_REMOTE_COMMAND_HISTORY_SIZE)
            remoteCommandHistoryJson = Gson().toJson(RemoteCommandHistoryData(history))
        } catch (e: Exception) {
            logger.debug("Failed to record remote command history", e)
        }
    }

    private fun readRemoteCommandHistory(): Map<String, List<String>> {
        return try {
            val historyJson = remoteCommandHistoryJson
            if (historyJson.isBlank()) {
                emptyMap()
            } else {
                Gson().fromJson(historyJson, RemoteCommandHistoryData::class.java)
                    ?.commandsByTarget
                    ?.mapValues { (_, commands) -> commands.filter(String::isNotBlank).take(MAX_REMOTE_COMMAND_HISTORY_SIZE) }
                    .orEmpty()
            }
        } catch (e: Exception) {
            logger.debug("Failed to read remote command history", e)
            emptyMap()
        }
    }

    @Synchronized
    private fun <T> read(name: String, defaultValue: T, fromJson: (JsonElement) -> T): T {
        val value = storage().values.get(name) ?: return defaultValue
        return try {
            fromJson(value)
        } catch (_: Exception) {
            defaultValue
        }
    }

    @Synchronized
    private fun <T> write(name: String, value: T, toJson: (T) -> JsonElement) {
        val jsonValue = toJson(value)
        val storage = storage()
        storage.values.add(name, jsonValue)
        storage.repository.update(name, jsonValue)
    }

    @Synchronized
    private fun storage(): Storage {
        val rootDir = JuggGlobalPathManager.rootDir
        storageCache?.takeIf { it.rootDir == rootDir }?.let { return it }
        return Storage(rootDir, createRepository(rootDir)).also {
            storageCache = it
        }
    }

    private fun createRepository(rootDir: File): JsonRuntimeSettingsRepository {
        return JsonRuntimeSettingsRepository(JuggGlobalPathManager.settingsFile(rootDir), rootDir, logger)
    }

    private fun setting(defaultValue: Boolean) = Setting(defaultValue, { it.asBoolean }, ::JsonPrimitive)
    private fun setting(defaultValue: String) = Setting(defaultValue, { it.asString }, ::JsonPrimitive)
    private fun setting(defaultValue: Long) = Setting(defaultValue, { it.asLong }, ::JsonPrimitive)
    private fun nullableStringSetting() = Setting<String?>(null, { if (it.isJsonNull) null else it.asString }, { it?.let(::JsonPrimitive) ?: JsonNull.INSTANCE })

    private class Storage(
        val rootDir: File,
        val repository: JsonRuntimeSettingsRepository,
        val values: JsonObject = repository.load(),
    )

    private class Setting<T>(
        private val defaultValue: T,
        private val fromJson: (JsonElement) -> T,
        private val toJson: (T) -> JsonElement,
    ) {
        operator fun getValue(owner: JuggSettings, property: KProperty<*>): T = owner.read(property.name, defaultValue, fromJson)
        operator fun setValue(owner: JuggSettings, property: KProperty<*>, value: T) = owner.write(property.name, value, toJson)
    }
}

private data class RemoteCommandHistoryData(
    val commandsByTarget: Map<String, List<String>> = emptyMap(),
)
