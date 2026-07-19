package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.git.FileMatcher
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.protocols.ProjectCustomConfig
import java.io.File
import java.util.zip.ZipFile

/** Owns project custom-config persistence and applies its effective snapshot to runtime collaborators. */
class ProjectCustomConfigManager(
    configDir: File,
    private val logger: Logger,
    private val juggServer: JuggServer,
    private val fileChangesHandler: IFileChangesHandler,
    private val deployHistoryManager: IDeployHistoryManager,
    private val compileContextManager: CompileContextManager,
    private val customCompilerManager: CustomCompilerManager,
) {

    private val store = ProjectCustomConfigStore(configDir, logger)

    fun refresh(): Boolean {
        return try {
            refreshInternal()
        } catch (exception: Exception) {
            logger.warn("Refresh custom config failed.", exception)
            false
        }
    }

    fun updateDefaultConfig(config: ProjectCustomConfig): Boolean {
        return try {
            store.updateDefaultConfig(config)
            refreshInternal()
        } catch (exception: Exception) {
            logger.warn("Update default custom config failed.", exception)
            false
        }
    }

    fun fillApkInfosWithEmbeddedApks(apkInfos: List<ApkInfo>, extractDir: File): List<ApkInfo> {
        return store.fillApkInfosWithEmbeddedApks(apkInfos, extractDir)
    }

    fun hasEmbeddedApks(): Boolean = store.hasEmbeddedApks()

    private fun refreshInternal(): Boolean {
        if (!store.isConfigChanged()) return false
        return try {
            apply(store.config ?: EMPTY_CONFIG)
            true
        } catch (exception: Exception) {
            store.invalidate()
            throw exception
        }
    }

    private fun apply(config: ProjectCustomConfig) {
        val moduleCustomConfigs = config.moduleCustomConfigs.orEmpty()
        juggServer.updateServer(config.servers)
        fileChangesHandler.updateBuildFileRules(config.buildFileRules, moduleCustomConfigs.map { it.moduleStdPath })
        deployHistoryManager.updateDontFilterIgnoredFileRules(config.dontFilterIgnoredFileRules)
        compileContextManager.updateCustomClasspath(moduleCustomConfigs)
        customCompilerManager.updateCustomCompilers(config.customCompilers.orEmpty())
    }

    companion object {
        private val EMPTY_CONFIG = ProjectCustomConfig(
            servers = null,
            buildFileList = emptyList(),
            buildFileRules = emptyList(),
            dontFilterIgnoredFileRules = emptyList(),
            moduleCustomConfigs = emptyList(),
            customCompilers = emptyList(),
            embeddedApksSearchRules = emptyList(),
        )
    }
}

/** Loads and caches the effective project custom-config files for [ProjectCustomConfigManager]. */
private class ProjectCustomConfigStore(
    private val configDir: File,
    private val logger: Logger,
) {

    init {
        configDir.mkdirs()
    }

    private val customConfigFile = File(configDir, "custom_config.json")
    private val defaultCustomConfigFile = File(configDir, "default_custom_config.json")
    private var cacheKey = "null"
    private var configCache: ProjectCustomConfig? = null

    val config: ProjectCustomConfig?
        get() {
            val newCacheKey = getCacheKey()
            if (cacheKey != newCacheKey) {
                logger.debug("Custom config changed from $cacheKey to $newCacheKey, reload it.")
                configCache = loadConfig()
                cacheKey = newCacheKey
            }
            return configCache
        }

    fun isConfigChanged(): Boolean = cacheKey != getCacheKey()

    fun invalidate() {
        cacheKey = "null"
    }

    fun updateDefaultConfig(customConfig: ProjectCustomConfig) {
        configDir.mkdirs()
        val oldContent = if (defaultCustomConfigFile.exists()) defaultCustomConfigFile.readText() else "{}"
        val newContent = Gson().toJson(customConfig)
        if (oldContent == newContent) {
            logger.debug("No need to update default custom config.")
            return
        }
        defaultCustomConfigFile.writeText(newContent)
    }

    fun fillApkInfosWithEmbeddedApks(apkInfos: List<ApkInfo>, extractDir: File): List<ApkInfo> {
        logger.debug("hasEmbeddedApks: ${hasEmbeddedApks()}")
        if (!hasEmbeddedApks()) return apkInfos

        val finalApkInfos = apkInfos.toMutableList()
        apkInfos.forEach { apkInfo ->
            apkInfo.files.forEach { apkFileUnit ->
                val embeddedApks = extractEmbeddedApks(apkFileUnit.apkFile, extractDir)
                logger.debug("find embeddedApks for ${apkFileUnit.apkFile}, result: $embeddedApks")
                ApkInfoReader(logger).createApkInfo(embeddedApks).forEach { embeddedApkInfo ->
                    val existingApkInfo = apkInfos.find { it.applicationId == embeddedApkInfo.applicationId }
                    if (existingApkInfo == null) {
                        finalApkInfos.add(embeddedApkInfo)
                    } else {
                        finalApkInfos.remove(existingApkInfo)
                        finalApkInfos.add(existingApkInfo.copy(files = existingApkInfo.files + embeddedApkInfo.files))
                    }
                }
            }
        }
        logger.debug("finalApkInfos: $finalApkInfos")
        return finalApkInfos
    }

    fun hasEmbeddedApks(): Boolean = config?.embeddedApksSearchRules?.isNotEmpty() ?: false

    private fun extractEmbeddedApks(apkFile: File, outputDir: File): List<File> {
        val rules = config?.embeddedApksSearchRules ?: return emptyList()
        if (rules.isEmpty()) return emptyList()
        val fileMatcher = FileMatcher().also { it.init(null, rules) }

        ZipFile(apkFile).use { zipFile ->
            val result = mutableListOf<File>()
            zipFile.entries().asIterator().forEach {
                if (fileMatcher.isMatch(it.name)) {
                    val outputFile = File(outputDir, apkFile.name + "_" + it.name)
                    outputFile.parentFile.mkdirs()
                    logger.debug("found embedded apk: ${it.name} in $apkFile, output to: $outputFile")
                    zipFile.getInputStream(it).use { inputStream ->
                        outputFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                    }
                    outputFile.setLastModified(it.time)
                    result.add(outputFile)
                }
            }
            return result
        }
    }

    private fun loadConfig(): ProjectCustomConfig? {
        if (customConfigFile.exists()) {
            try {
                return Gson().fromJson(customConfigFile.readText(), ProjectCustomConfig::class.java).also { logger.debug("Load custom config: $it") }
            } catch (exception: Exception) {
                logger.warn("Load custom config failed.", exception)
            }
        }
        if (defaultCustomConfigFile.exists()) {
            try {
                return Gson().fromJson(defaultCustomConfigFile.readText(), ProjectCustomConfig::class.java).also { logger.debug("Load default custom config: $it") }
            } catch (exception: Exception) {
                logger.warn("Load default custom config failed.", exception)
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
