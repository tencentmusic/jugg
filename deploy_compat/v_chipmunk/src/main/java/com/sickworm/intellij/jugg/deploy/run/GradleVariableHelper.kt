package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.intellij.openapi.diagnostic.Logger

class GradleVariableHelper(private val logger: Logger) {

    fun readVariable(model: GradleBuildModel, propertyGetter: () -> ResolvedPropertyModel, isValid: String.() -> Boolean): String? {
        try {
            val property = propertyGetter()
            return readVariable(model, property, isValid)
        } catch (e: Throwable) {
            logger.warn("readVariable get failed: $e")
            return null
        }
    }

    fun <T> readVariable(propertyGetter: () -> T): T? {
        try {
            return propertyGetter()
        } catch (e: Throwable) {
            logger.warn("readVariable2 get failed: $e")
            return null
        }
    }

    private fun readVariable(model: GradleBuildModel, property: ResolvedPropertyModel, isValid: String.() -> Boolean): String? {
        val value = property.valueAsString()?.trim() ?: return null
        return readVariable(value, model, isValid)
    }

    private fun readVariable(value: String, model: GradleBuildModel, isValid: String.() -> Boolean): String? {
        // mostly, variable can be parsed by IDE
        if (value.isValid()) {
            return value
        }

        // parse will fail if variable contains " as "
        var fixedValue = value
        if (fixedValue.contains(" as ")) {
            val index = value.indexOf(" as ")
            fixedValue = value.substring(0, index)
        }

        // after remove " as ", find it in declaredProperties first, which is defined in current build.gradle
        val declaredProperty = model.declaredProperties.find { it.name == fixedValue }
        declaredProperty?.valueAsString()?.let {
            if (it.isValid()) {
                return it
            }
        }
        return null
    }

}