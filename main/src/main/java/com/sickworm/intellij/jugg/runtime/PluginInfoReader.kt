package com.sickworm.intellij.jugg.runtime

import java.util.jar.Manifest

/**
 * PluginInfoReader reads plugin metadata from `META-INF/JUGG_PLUGIN_INFO.MF`.
 */
object PluginInfoReader {

    private val juggPluginInfoManifest: Manifest? by lazy {
        val cl = PluginInfoReader::class.java.classLoader
        cl.getResourceAsStream("META-INF/JUGG_PLUGIN_INFO.MF")?.use {
            return@lazy Manifest(it)
        }
        null
    }

    fun getPluginCompileInfo(): String {
        val stringBuilder = StringBuilder()
        if (juggPluginInfoManifest == null) {
            stringBuilder.append("juggPluginInfoManifest not found")
        } else if (juggPluginInfoManifest?.mainAttributes.isNullOrEmpty()) {
            stringBuilder.append("juggPluginInfoManifest.mainAttributes not found")
        }
        juggPluginInfoManifest?.mainAttributes?.forEach {
            stringBuilder.append("${it.key}: ${it.value}, ")
        }
        return stringBuilder.toString()
    }

    fun getPluginVersion(): String {
        return juggPluginInfoManifest?.mainAttributes?.getValue("Version") ?: "unknown"
    }

    fun getPluginCompileTimestamp(): String {
        return juggPluginInfoManifest?.mainAttributes?.getValue("Compile-Timestamp").orEmpty()
    }
}
