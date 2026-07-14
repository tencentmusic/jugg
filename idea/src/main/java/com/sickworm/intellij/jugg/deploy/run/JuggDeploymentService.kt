package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.model.Apk
import com.android.utils.ILogger
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.CachedOverlayId
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

/**
 * Provides the project-scoped deployment cache used by IDEA deploy and recover flows.
 * Disk snapshots are refreshed under the shared project lock so another runtime can safely take over.
 */
class JuggDeploymentService(
    pathManager: JuggPathManager,
    private val deploymentCacheStore: JuggDeploymentCacheStore,
) : IJuggDeploymentService, IJuggDeployerDeploymentService {

    val deploymentCacheDbFile = pathManager.deploymentCacheDbFile
    private val memoryCache = ConcurrentHashMap<String, JuggDeploymentCacheEntry>()

    fun preInit(logger: Logger) {
        val costTime = measureTimeMillis {
            deploymentCacheStore.preInit()
        }
        logger.debug("JuggDeploymentService.preInit, cost ${costTime}ms")
    }

    override fun storeEntry(deviceSerial: String, packageName: String, newFiles: List<Apk>, overlayId: JuggOverlayId, logger: ILogger) {
        val storeStartTime = System.currentTimeMillis()
        val key = "$deviceSerial:$packageName"

        memoryCache[key] = AsDeployerCompat.createDeploymentCacheEntry(newFiles, overlayId)
        logger.info("JuggDeploymentService.storeEntry, start")
        deploymentCacheStore.store(deviceSerial, packageName, newFiles.toCacheEntry(overlayId))
        logger.info("JuggDeploymentService.storeEntry, end, costTime: ${System.currentTimeMillis() - storeStartTime}ms")
    }

    override fun loadCachedOverlayId(deviceSerial: String, packageName: String, logger: Logger): CachedOverlayId? {
        return loadEntry(deviceSerial, packageName, AdbLogWrapper(logger))
            ?.overlayId
            ?.let { CachedOverlayId(sha = it.sha, isBaseInstall = it.isBaseInstall) }
    }

    override fun loadEntry(deviceSerial: String, packageName: String, logger: ILogger): JuggDeploymentCacheEntry? {
        memoryCache["$deviceSerial:$packageName"]?.let {
            logger.info("JuggDeploymentService.loadEntry, load from memory cache")
            return it
        }
        val dbResult = deploymentCacheStore.load(deviceSerial, packageName)
            ?.toDeploymentCacheEntry()
        logger.info("JuggDeploymentService.loadEntry, load from db, result: $dbResult")
        return dbResult
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

    private fun JuggDeploymentCacheStore.CacheEntry.toDeploymentCacheEntry(): JuggDeploymentCacheEntry {
        val parsedApks = AsDeployerCompat.parseApks(apkPaths)
        val baseOverlayId = AsDeployerCompat.createBaseOverlayId(parsedApks)
        val restoredOverlayId = if (overlayId.isBaseInstall) {
            baseOverlayId
        } else {
            AsDeployerCompat.buildOverlayId(
                baseOverlayId,
                overlayId.overlayFiles.map { JuggOverlayFile(it.path, it.checksum) },
            )
        }
        return AsDeployerCompat.createDeploymentCacheEntry(parsedApks, restoredOverlayId)
    }
}
