package com.sickworm.intellij.jugg.mock

import com.intellij.ide.util.PropertiesComponent

class DummyPropertiesComponent: PropertiesComponent() {

    private val map: MutableMap<String, String?> = mutableMapOf()

    override fun unsetValue(name: String) {
        map.remove(name)
    }

    override fun isValueSet(name: String): Boolean {
        return map.containsKey(name)
    }

    override fun getValue(name: String): String? {
        return map[name]
    }

    override fun setValue(name: String, value: String?) {
        map[name] = value
    }

    override fun setValue(name: String, value: String?, defaultValue: String?) {
        if (value == null || value == defaultValue) {
            unsetValue(name)
        }
        map[name] = value
    }

    override fun setValue(name: String, value: Float, defaultValue: Float) {
        map[name] = value.toString()
    }

    override fun setValue(name: String, value: Int, defaultValue: Int) {
        map[name] = value.toString()
    }

    override fun setValue(name: String, value: Boolean, defaultValue: Boolean) {
        if (value == defaultValue) {
            unsetValue(name)
        }
        map[name] = value.toString()
    }

    override fun getValues(name: String): Array<String>? {
        throw IllegalArgumentException("not supported")
    }

    override fun setValues(name: String, values: Array<out String>?) {
        throw IllegalArgumentException("not supported")
    }

}