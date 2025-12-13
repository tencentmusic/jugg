package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File
import java.util.Properties

/**
 * Disable configuration cache when gradle.properties enable configuration cache
 */
@Suppress("unused")
object ConfigurationCacheCompatHelper {

    // works on gradle 8.9. when starts? no idea
    const val CONFIGURATION_CACHE_ARG = "org.gradle.configuration-cache"
    const val CONFIGURATION_CACHE_WARN_ONLY_ARG = "org.gradle.configuration-cache.problems" // yes, it's different

    // works in gradle 7.0 ~ 8.9. when ends? no idea
    const val OLD_CONFIGURATION_CACHE_ARG = "org.gradle.unsafe.configuration-cache"
    const val OLD_CONFIGURATION_CACHE_WARN_ONLY_ARG = "org.gradle.unsafe.configuration-cache-problems" // yes, it's different

    fun getDisableArgsIfEnabled(projectDir: File, compileCommand: String, loggerArg: Logger? = null): String {
        val logger = loggerArg?.getInstance("ConfigurationCacheCompatHelper")
        val gradlePropertiesFile = File(projectDir, "gradle.properties")
        if (!gradlePropertiesFile.exists()) {
            logger?.debug("isNeedAddArg gradlePropertiesFile $gradlePropertiesFile not exists")
            return ""
        }
        if (compileCommand.contains(CONFIGURATION_CACHE_ARG) || compileCommand.contains(CONFIGURATION_CACHE_WARN_ONLY_ARG)) {
            logger?.debug("isNeedAddArg compileCommand $compileCommand contains $CONFIGURATION_CACHE_ARG or $CONFIGURATION_CACHE_WARN_ONLY_ARG")
            return ""
        }

        try {
            val properties = Properties()
            properties.load(gradlePropertiesFile.readText().byteInputStream())
            val isCacheEnable = properties.getProperty(CONFIGURATION_CACHE_ARG)
            val isOldCacheEnable = properties.getProperty(OLD_CONFIGURATION_CACHE_ARG)
            logger?.debug("isNeedAddArg isCacheEnable $isCacheEnable, isOldCacheEnable $isOldCacheEnable")
            return if (isCacheEnable == "true") {
                " -D$CONFIGURATION_CACHE_ARG=false"
            } else if (isOldCacheEnable == "true") {
                " -D$OLD_CONFIGURATION_CACHE_ARG=false"
            } else {
                ""
            }
        } catch (e: Exception) {
            logger?.debug("isNeedAddArg exception ", e)
            return ""
        }
    }

}