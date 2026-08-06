package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.CachedOverlayId
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

/**
 * Provides the project-scoped deployment cache used by IDEA deploy and recover flows.
 * Disk snapshots are refreshed under the shared project lock so another runtime can safely take over.
 */
class JuggDeploymentService(
    pathManager: JuggPathManager,
    private val deploymentCacheStore: JuggDeploymentCacheStore,
    private val defaultApplyChangesExecutor: IApplyChangesExecutor,
) : IJuggDeploymentService, IJuggDeployerDeploymentService {

    val deploymentCacheDbFile = pathManager.deploymentCacheDbFile
    private val memoryCache = ConcurrentHashMap<String, RuntimeCacheEntry>()
    private var memoryCacheGeneration = deploymentCacheStore.currentState().generation

    fun preInit(logger: Logger) {
        val costTime = measureTimeMillis {
            deploymentCacheStore.preInit()
        }
        logger.debug("JuggDeploymentService.preInit, cost ${costTime}ms")
    }

    override fun storeEntry(
        deviceSerial: String, packageName: String, newFiles: List<Apk>, overlayId: JuggOverlayId,
        applyChangesExecutor: IApplyChangesExecutor, logger: ILogger,
    ) {
        val storeStartTime = System.currentTimeMillis()
        val key = "$deviceSerial:$packageName"

        val cacheEntry = applyChangesExecutor.createDeploymentCacheEntry(newFiles, overlayId)
        logger.info("JuggDeploymentService.storeEntry, start")
        deploymentCacheStore.store(deviceSerial, packageName, newFiles.toCacheEntry(overlayId))
        refreshMemoryCacheState()
        memoryCache[key] = RuntimeCacheEntry(applyChangesExecutor, cacheEntry)
        logger.info("JuggDeploymentService.storeEntry, end, costTime: ${System.currentTimeMillis() - storeStartTime}ms")
    }

    override fun loadCachedOverlayId(deviceSerial: String, packageName: String, logger: Logger): CachedOverlayId? {
        return loadEntry(deviceSerial, packageName, defaultApplyChangesExecutor, AdbLogWrapper(logger))
            ?.overlayId
            ?.let { CachedOverlayId(sha = it.sha, isBaseInstall = it.isBaseInstall) }
    }

    override fun loadEntry(
        deviceSerial: String, packageName: String,
        applyChangesExecutor: IApplyChangesExecutor, logger: ILogger,
    ): JuggDeploymentCacheEntry? {
        refreshMemoryCacheState()
        val key = "$deviceSerial:$packageName"
        memoryCache[key]?.takeIf { it.applyChangesExecutor === applyChangesExecutor }?.let {
            logger.info("JuggDeploymentService.loadEntry, load from memory cache")
            return it.entry
        }
        val dbResult = deploymentCacheStore.load(deviceSerial, packageName)
            ?.toDeploymentCacheEntry(applyChangesExecutor)
        if (dbResult != null) memoryCache[key] = RuntimeCacheEntry(applyChangesExecutor, dbResult)
        logger.info("JuggDeploymentService.loadEntry, load from db, result: $dbResult")
        return dbResult
    }

    private fun refreshMemoryCacheState() {
        val state = deploymentCacheStore.currentState()
        if (!state.runtimeOwnerChanged && state.generation == memoryCacheGeneration) return
        memoryCache.clear()
        memoryCacheGeneration = state.generation
    }

    private fun List<Apk>.toCacheEntry(overlayId: JuggOverlayId): JuggDeploymentCacheStore.CacheEntry {
        return JuggDeploymentCacheStore.CacheEntry(
            apkPaths = map { it.path },
            overlayId = JuggDeploymentCacheStore.OverlayId(
                sha = overlayId.sha,
                isBaseInstall = overlayId.isBaseInstall,
                overlayFiles = overlayId.overlayFiles.map {
                    JuggDeploymentCacheStore.OverlayFile(it.path, it.checksum)
                },
            ),
        )
    }

    private fun JuggDeploymentCacheStore.CacheEntry.toDeploymentCacheEntry(
        applyChangesExecutor: IApplyChangesExecutor,
    ): JuggDeploymentCacheEntry {
        val parsedApks = applyChangesExecutor.parseApks(apkPaths)
        val baseOverlayId = applyChangesExecutor.createBaseOverlayId(parsedApks)
        val restoredOverlayId = if (overlayId.isBaseInstall) {
            baseOverlayId
        } else {
            applyChangesExecutor.buildOverlayId(baseOverlayId,
                overlayId.overlayFiles.map { JuggOverlayFile(it.path, it.checksum) })
        }
        return applyChangesExecutor.createDeploymentCacheEntry(parsedApks, restoredOverlayId)
    }

    private data class RuntimeCacheEntry(
        val applyChangesExecutor: IApplyChangesExecutor,
        val entry: JuggDeploymentCacheEntry,
    )
}
