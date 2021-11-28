package com.sickworm.intellij.jugg.mock

import com.intellij.ide.util.PropertiesComponent

class DummyPropertiesComponent: PropertiesComponent() {
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