@file:Suppress("ConstPropertyName")

package com.sickworm.intellij.jugg.ide

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.ide.util.PropertiesComponent
import com.sickworm.intellij.jugg.server.listType
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import com.sickworm.intellij.jugg.server.typeAdapter
import kotlin.reflect.KProperty

object JuggSettings {

    private val propertiesComponent get() = PropertiesComponent.getInstance()

    var compileOnSave: Boolean by propertiesComponent.delegate(defaultValue = false)

    var deployOnSave: Boolean by propertiesComponent.delegate(defaultValue = false)

    // default compile settings
    private var defaultCompileSettingsJson: String by propertiesComponent.delegate(defaultValue = "")
    private var compileTemplateListJson: String by propertiesComponent.delegate(defaultValue = "")

    var isConfirmFallbackWhenNoFileChanges: Boolean by propertiesComponent.delegate(defaultValue = true)

    /** whether check checksum to make sure file is really change when file changes */
    var isCheckChecksumWhenFileChanges: Boolean by propertiesComponent.delegate(defaultValue = true)

    /**
     * Enable init gradle scripts when gradle compile.
     * Effected function: [isEnableReadProjectInfoFromGradle], [isEnableCompatibleDeploymentMode]
     */
    var isEnableInjectGradleCompile: Boolean by propertiesComponent.delegate(defaultValue = true)

    /**
     * Enable read project info from gradle.
     */
    var isEnableReadProjectInfoFromGradle: Boolean by propertiesComponent.delegate(defaultValue = true)
    val finalIsEnableReadProjectInfoFromGradle get() = isEnableInjectGradleCompile && isEnableReadProjectInfoFromGradle

    /**
     * compat deploy strategy for:
     * Huawei HarmonyOS 4.2
     * Xiaomi HyperOS
     * Device API lower than 30
     * For Huawei HarmonyOS 4.2, Jugg will fix the problem that incremental dex not inject correctly.
     * For Xiaomi HyperOS, some apps may get "MISSING_AGENT_RESPONSES", Jugg will use hot fix deployment solution to compat with it.
     * For Device API lower than 30, Jugg will use hot fix deployment solution to compat with it.
     */
    var isEnableCompatibleDeploymentMode: Boolean by propertiesComponent.delegate(defaultValue = true)
    val finalIsEnableCompatibleDeploymentMode get() = isEnableInjectGradleCompile && isEnableCompatibleDeploymentMode

    /** limit max source modules to compile for better performance */
    const val maxCompileSourceModules = 50
    /** limit max source files to compile for better performance */
    const val maxCompileSourceFiles = 150
    /** limit min compiler error to recreate once */
    const val minErrorToRecreateCompiler = 30

    /** whether deploy all res files to device after install apk */
    var isEnableWarmUpDeploy: Boolean = false

    /**
     * Whether fallback all to hot fix if there is any hot fix files.
     * Enable this can skip JVM-TI process and save time.
     */
    const val isQuickFallbackToHotFix: Boolean = true

    var deviceCompatRecordJson: String by propertiesComponent.delegate(defaultValue = "")

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
                } catch (e: Exception) {
                    // ignore
                }
            } else if (compileTemplateList.isNotEmpty()) {
                // use first template settings
                default = compileTemplateList.first()
            }
            return default
        }
        set(value) {
            defaultCompileSettingsJson = Gson().toJson(value)
        }

    private var compileTemplateListCache: List<RunConfigurationTemplate>? = null
    var compileTemplateList: List<RunConfigurationTemplate>
        get() {
            compileTemplateListCache?.let {
                return it
            }
            val compileTemplateList = compileTemplateListJson
            if (compileTemplateList.isEmpty()) {
                return emptyList()
            }

            try {
                compileTemplateListCache = GsonBuilder()
                    .registerTypeAdapter(String::class.java, RunConfigurationTemplate.typeAdapter)
                    .create()
                    .fromJson(compileTemplateList, RunConfigurationTemplate.listType)
            } catch (e: Exception) {
                // ignore
            }
            return compileTemplateListCache ?: emptyList()
        }
        set(value) {
            compileTemplateListCache = null // don't save it directly, because value won't be processed by typeAdapter
            compileTemplateListJson = Gson().toJson(value)
        }

    init {
        // compat with old version of Jugg
        if (defaultCompileSettingsJson.isEmpty() && propertiesComponent.getValue("defaultCompileCommand", "") != "") {
            val recoverTemplate = RunConfigurationTemplate(
                "Default",
                propertiesComponent.getValue("defaultCompileCommand"),
                propertiesComponent.getValue("defaultOutputApkName"),
                propertiesComponent.getBoolean("defaultIsRemoteCompile"),
                propertiesComponent.getValue("defaultRemoteSshUser"),
                propertiesComponent.getValue("defaultRemoteSshIp"),
                propertiesComponent.getValue("defaultRemoteSshPassword"),
                propertiesComponent.getInt("defaultRemoteSshPort", 0),
                propertiesComponent.getValue("defaultLocalToRemoteIftConfigName"),
                propertiesComponent.getValue("defaultLocalToRemoteSyncPath"),
                propertiesComponent.getValue("defaultRemoteSyncPath"),
                propertiesComponent.getValue("defaultRemoteToLocalIftConfigName"),
                propertiesComponent.getValue("defaultRemoteToLocalSyncPath"),
                propertiesComponent.getValue("defaultHttpProxyIp"),
                propertiesComponent.getInt("defaultHttpProxyPort", 0),
                propertiesComponent.getBoolean("defaultIsSyncAllProjects"),
                SyncMode.IFT.modeName,
            )
            defaultCompileSettingsJson = Gson().toJson(recoverTemplate)
        }
    }
}

/**
 * Use PropertiesComponent to delegate variable.
 */
private fun PropertiesComponent.delegate(keyName: String? = null, defaultValue: Any? = null): PropertiesDelegate {
    return PropertiesDelegate(this, keyName, defaultValue)
}

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

    fun doGetValue(name: String, clazz: Class<*>): Any {
        return  when (clazz) {
            java.lang.Integer::class.java, Int::class.java -> propertiesComponent.getInt(name, (defaultValue as? Int?: 0))
            java.lang.Float::class.java, Float::class.java -> propertiesComponent.getFloat(name, (defaultValue as? Float?: 0f))
            java.lang.Boolean::class.java, Boolean::class.java -> propertiesComponent.getBoolean(name, (defaultValue as? Boolean?: false))
            String::class.java -> propertiesComponent.getValue(name, (defaultValue as? String?: ""))
            else -> throw IllegalArgumentException("PropertiesDelegate not support class $clazz")
        }
    }

    inline operator fun <reified T> setValue(obj: Any, property: KProperty<*>, i: T) {
        val name = "jugg." + (keyName?: property.name)
        doSetValue(name, T::class.java, i)
    }

    fun <T> doSetValue(name: String, clazz: Class<*>, i: T) {
        return when (clazz) {
            java.lang.Integer::class.java, Int::class.java -> propertiesComponent.setValue(name, i as Int, (defaultValue as? Int?: 0))
            java.lang.Float::class.java, Float::class.java -> propertiesComponent.setValue(name, i as Float, (defaultValue as? Float?: 0f))
            java.lang.Boolean::class.java, Boolean::class.java -> propertiesComponent.setValue(name, i as Boolean, (defaultValue as? Boolean?: false))
            String::class.java -> propertiesComponent.setValue(name, i as String, (defaultValue as? String?: ""))
            else -> throw IllegalArgumentException("PropertiesDelegate not support class $clazz")
        }
    }
}
