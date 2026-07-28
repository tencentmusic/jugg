@file:Suppress("ConstPropertyName", "SimplifyBooleanWithConstants", "KotlinConstantConditions")

package com.sickworm.intellij.jugg.ide.bean

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.ide.util.PropertiesComponent
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import com.sickworm.intellij.jugg.server.typeAdapter
import kotlin.reflect.KProperty

/**
 * JuggSettings stores persisted and effective settings that control Jugg compile/deploy behavior.
 * Collaboration: Fields are persisted through [PropertiesDelegate], then consumed by IDE/server/compiler/deploy flows.
 * Data Contract: Persisted entries are keyed under `jugg.*`; `final*` getters expose effective switches composed from base flags.
 */
object JuggSettings {

    private val propertiesComponent get() = PropertiesComponent.getInstance()

    var compileOnSave: Boolean by propertiesComponent.delegate(defaultValue = false)

    var deployOnSave: Boolean by propertiesComponent.delegate(defaultValue = false)

    // default compile settings
    private var defaultCompileSettingsJson: String by propertiesComponent.delegate(defaultValue = "")


    // Run options start

    var isConfirmFallbackWhenNoFileChanges: Boolean by propertiesComponent.delegate(defaultValue = true)

    var isAlwaysRestartAppAfterDeployment: Boolean by propertiesComponent.delegate(defaultValue = false)

    var isAutoFallbackToGradleWhenDeployError: Boolean by propertiesComponent.delegate(defaultValue = false)

    var isEmbeddedToApk: Boolean by propertiesComponent.delegate(defaultValue = false)

    // Run options end

    /** whether check checksum to make sure file is really change when file changes */
    var isCheckChecksumWhenFileChanges: Boolean by propertiesComponent.delegate(defaultValue = true)

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
    var isEnableCompatibleDeploymentMode: Boolean = true
    val finalIsEnableCompatibleDeploymentMode get() = isEnableInjectGradleCompile && isEnableCompatibleDeploymentMode

    /**
     * Enables direct overlay deploy shortcuts that do not require the app process to be online.
     */
    var isEnableDirectOverlayDeploy: Boolean by propertiesComponent.delegate(defaultValue = true)

    var isUseProjectKotlinCompiler: Boolean by propertiesComponent.delegate(keyName = "isUseProjectKotlinCompiler_v3", defaultValue = true)

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

    var isEnableBackupClasspath: Boolean by propertiesComponent.delegate(keyName = "isEnableBackupClasspath_v2", defaultValue = false)
    // windows not support rsync, so disable backup classpath
    var isCanUseBackupClasspath: Boolean = !isWindows

    var deviceCompatRecordJson: String by propertiesComponent.delegate(defaultValue = "")

    var sliceDeployRecordJson: String by propertiesComponent.delegate(defaultValue = "")

    var isIgnoreWontCompileModules: Boolean by propertiesComponent.delegate(defaultValue = false)

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

    var serverUrl: String? by propertiesComponent.delegate(defaultValue = null)
    var serverExpireTimeMill: Long by propertiesComponent.delegate(defaultValue = 0L)
}

/**
 * Use PropertiesComponent to delegate variable.
 */
private fun PropertiesComponent.delegate(keyName: String? = null, defaultValue: Any? = null): PropertiesDelegate {
    return PropertiesDelegate(this, keyName, defaultValue)
}

/**
 * PropertiesDelegate property delegate that bridges Kotlin properties to [PropertiesComponent] key-value storage.
 * Data Contract: Supports Int/Float/Boolean/Long/String only; unsupported types throw [IllegalArgumentException].
 */
private class PropertiesDelegate(
    private val propertiesComponent: PropertiesComponent,
    /** property key，use KProperty.name if not specific，KProperty.name is the name of variable and won't change if use proguard */
    private val keyName: String? = null,
    /** default value. use internal default value if not specific. */
    private val defaultValue: Any? = null
) {

    inline operator fun <reified T> getValue(obj: Any, property: KProperty<*>): T {
        val name = "jugg." + (keyName?: property.name)
        return doGetValue(name, T::class.java) as T
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
    fun doGetValue(name: String, clazz: Class<*>): Any {
        return  when (clazz) {
            java.lang.Integer::class.java, Int::class.java -> propertiesComponent.getInt(name, (defaultValue as? Int?: 0))
            java.lang.Float::class.java, Float::class.java -> propertiesComponent.getFloat(name, (defaultValue as? Float?: 0f))
            java.lang.Boolean::class.java, Boolean::class.java -> propertiesComponent.getBoolean(name, (defaultValue as? Boolean?: false))
            java.lang.Long::class.java, Long::class.java -> {
                val defaultValue = defaultValue as? Long ?: 0L
                propertiesComponent.getValue(name, defaultValue.toString()).toLongOrNull() ?: defaultValue
            }
            String::class.java -> propertiesComponent.getValue(name, (defaultValue as? String?: ""))
            else -> throw IllegalArgumentException("PropertiesDelegate not support class $clazz")
        }
    }

    inline operator fun <reified T> setValue(obj: Any, property: KProperty<*>, i: T) {
        val name = "jugg." + (keyName?: property.name)
        doSetValue(name, T::class.java, i)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
    fun <T> doSetValue(name: String, clazz: Class<*>, i: T) {
        return when (clazz) {
            java.lang.Integer::class.java, Int::class.java -> propertiesComponent.setValue(name, i as Int, (defaultValue as? Int?: 0))
            java.lang.Float::class.java, Float::class.java -> propertiesComponent.setValue(name, i as Float, (defaultValue as? Float?: 0f))
            java.lang.Boolean::class.java, Boolean::class.java -> propertiesComponent.setValue(name, i as Boolean, (defaultValue as? Boolean?: false))
            java.lang.Long::class.java, Long::class.java -> propertiesComponent.setValue(name, i.toString(), (defaultValue as? Long ?: 0L).toString())
            String::class.java -> propertiesComponent.setValue(name, i as String?, (defaultValue as? String?: ""))
            else -> throw IllegalArgumentException("PropertiesDelegate not support class $clazz")
        }
    }
}
