package com.sickworm.intellij.jugg.project

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.server.protocols.ProjectCustomConfig
import java.io.File

class CustomConfigManager(
    private val configDir: File,
    private val logger: Logger,
) {

    init {
        configDir.mkdirs()
    }

    private val customConfigFile: File = File(configDir, "custom_config.json")

    private val defaultCustomConfigFile: File = File(configDir, "default_custom_config.json")

    private var cacheKey: String = "null"
    private var configCache: ProjectCustomConfig? = null

    val config: ProjectCustomConfig?
        get() {
            val newCacheKey = getCacheKey()
            if (cacheKey != newCacheKey) {
                logger.debug("Custom config changed from $cacheKey to $newCacheKey, reload it.")
                configCache = loadDefaultConfig()
                cacheKey = newCacheKey
            }
            return configCache
        }

    fun isConfigChanged(): Boolean {
        val newCacheKey = getCacheKey()
        return cacheKey != newCacheKey
    }

    fun updateDefaultConfig(customConfig: ProjectCustomConfig) {
        configDir.mkdirs()
        defaultCustomConfigFile.writeText(Gson().toJson(customConfig))
    }

    private fun loadDefaultConfig(): ProjectCustomConfig? {
        if (customConfigFile.exists()) {
            try {
                val result = Gson().fromJson(customConfigFile.readText(), ProjectCustomConfig::class.java)
                logger.debug("Load custom config: $result")
                return result
            } catch (e: Exception) {
                logger.warn("Load custom config failed.", e)
            }
        }

        if (defaultCustomConfigFile.exists()) {
            try {
                val result = Gson().fromJson(defaultCustomConfigFile.readText(), ProjectCustomConfig::class.java)
                logger.debug("Load default custom config: $result")
                return result
            } catch (e: Exception) {
                logger.warn("Load default custom config failed.", e)
            }
        }

        logger.debug("No custom config found.")
        return null
    }

    private fun getCacheKey(): String {
        return listOf(
            if (customConfigFile.exists()) customConfigFile.lastModified() else 0,
            if (defaultCustomConfigFile.exists()) defaultCustomConfigFile.lastModified() else 0,
        ).joinToString("_")
    }
}