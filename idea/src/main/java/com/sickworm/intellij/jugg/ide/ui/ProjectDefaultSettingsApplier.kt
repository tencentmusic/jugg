package com.sickworm.intellij.jugg.ide.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.server.protocols.ProjectCustomConfig
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties

/** Applies each project default-settings version once without changing global setting storage. */
class ProjectDefaultSettingsApplier(private val project: Project, private val logger: Logger) {
    fun apply(config: ProjectCustomConfig): Boolean {
        val version = config.defaultSettingsVersion ?: return false
        val settings = config.defaultSettings ?: return false
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getValue(VERSION_KEY)?.toIntOrNull() == version) return false
        applySettings(settings)
        properties.setValue(VERSION_KEY, version.toString())
        logger.debug("Applied project default settings version $version")
        return true
    }

    private fun applySettings(settings: Map<String, Any?>) {
        val properties = JuggSettings::class.memberProperties
            .filterIsInstance<KMutableProperty1<JuggSettings, *>>()
            .filter { it.visibility == KVisibility.PUBLIC && it.setter.visibility == KVisibility.PUBLIC }
            .associateBy { it.name }
        settings.forEach { (key, value) ->
            val property = properties[key]
            if (property == null) {
                logger.debug("Failed to apply project default setting $key: writable property not found")
                return@forEach
            }
            val converted = convertValue(property, value)
            if (converted === INVALID_VALUE) {
                logger.debug("Failed to apply project default setting $key: incompatible value type")
                return@forEach
            }
            try {
                property.setter.call(JuggSettings, converted)
                logger.debug("Applied project default setting $key")
            } catch (e: Exception) {
                logger.debug("Failed to apply project default setting $key", e)
                throw e
            }
        }
    }

    private fun convertValue(property: KMutableProperty1<JuggSettings, *>, value: Any?): Any? {
        if (value == null) return INVALID_VALUE
        val number = when (value) {
            is Double, is Float, is Long, is Int, is Short, is Byte -> value as Number
            else -> null
        }
        val double = number?.toDouble()
        return when (property.returnType.classifier) {
            Boolean::class -> value as? Boolean ?: INVALID_VALUE
            String::class -> value as? String ?: INVALID_VALUE
            Int::class -> if (double != null && double.isFinite() && double % 1.0 == 0.0 &&
                double >= Int.MIN_VALUE && double <= Int.MAX_VALUE) double.toInt() else INVALID_VALUE
            Long::class -> if (number is Long) number else if (double != null && double.isFinite() &&
                double % 1.0 == 0.0 && double >= -9_007_199_254_740_992.0 &&
                double <= 9_007_199_254_740_992.0
            ) double.toLong() else INVALID_VALUE
            Float::class -> if (double != null && double.isFinite() &&
                double.toFloat().isFinite() && double.toFloat().toDouble() == double
            ) double.toFloat() else INVALID_VALUE
            else -> INVALID_VALUE
        }
    }

    companion object {
        private const val VERSION_KEY = "jugg.defaultSettingsVersion"
        private val INVALID_VALUE = Any()
    }
}
