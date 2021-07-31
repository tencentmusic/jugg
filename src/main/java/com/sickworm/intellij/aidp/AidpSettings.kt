package com.sickworm.intellij.aidp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import kotlin.reflect.KProperty

object AidpSettings {

    private val isTestEnv get() = ApplicationManager.getApplication() == null

    private val propertiesComponent get() = if (isTestEnv) DummyPropertiesComponent() else PropertiesComponent.getInstance()

    var logDebug: Boolean by propertiesComponent.delegate(defaultValue = false)

    var deployOnSave: Boolean by propertiesComponent.delegate(defaultValue = false)
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

    @Suppress("UNCHECKED_CAST")
    inline operator fun <reified T> getValue(obj: Any, property: KProperty<*>): T {
        val name = "aidp." + (keyName?: property.name)
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
        val name = "aidp." + (keyName?: property.name)
        doSetValue(name, T::class.java, i)
    }

    fun <T> doSetValue(name: String, clazz: Class<*>, i: T) {
        return when (clazz) {
            java.lang.Integer::class.java, Int::class.java -> propertiesComponent.setValue(name, i as Int, (defaultValue as? Int?: 0))
            java.lang.Float::class.java, Float::class.java -> propertiesComponent.setValue(name, i as Float, (defaultValue as? Float?: 0f))
            java.lang.Boolean::class.java, Boolean::class.java -> propertiesComponent.setValue(name, i as Boolean, (defaultValue as? Boolean?: false))
            String::class.java -> propertiesComponent.setValue(name, (defaultValue as? String?: ""))
            else -> throw IllegalArgumentException("PropertiesDelegate not support class $clazz")
        }
    }
}

private class DummyPropertiesComponent: PropertiesComponent() {
    override fun unsetValue(name: String) {

    }

    override fun isValueSet(name: String): Boolean {
        return false
    }

    override fun getValue(name: String): String? {
        return null
    }

    override fun setValue(name: String, value: String?) {
    }

    override fun setValue(name: String, value: String?, defaultValue: String?) {
    }

    override fun setValue(name: String, value: Float, defaultValue: Float) {
    }

    override fun setValue(name: String, value: Int, defaultValue: Int) {
    }

    override fun setValue(name: String, value: Boolean, defaultValue: Boolean) {
    }

    override fun getValues(name: String): Array<String>? {
        return null
    }

    override fun setValues(name: String, values: Array<out String>?) {
    }

}