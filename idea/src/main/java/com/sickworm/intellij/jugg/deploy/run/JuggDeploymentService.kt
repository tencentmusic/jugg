package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.model.Apk
import com.android.utils.ILogger
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.CachedOverlayId
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

/**
 * copied from [com.android.tools.idea.run.DeploymentService]
 * * optimize deployment cache size
 * * provides synchronized and async api
 * * provides preInit
 */
object JuggDeploymentService : IJuggDeploymentService, IJuggDeployerDeploymentService {

    private val lock = Object()
    val deploymentCacheDbFile = JuggGlobalPathManager.deployCacheDbFile

    private val deploymentCacheStore: JuggDeploymentCacheStore by lazy {
        JuggDeploymentCacheStore(deploymentCacheDbFile)
    }

    private var memoryCache: ConcurrentHashMap<String, JuggDeploymentCacheEntry> = ConcurrentHashMap()

    fun preInit(logger: Logger) {
        postWithLock {
            val costTime = measureTimeMillis {
                deploymentCacheStore.preInit()
            }
            logger.debug("JuggDeploymentService.preInit, cost ${costTime}ms")
        }
    }

    override fun storeEntry(deviceSerial: String, packageName: String, newFiles: List<Apk>, overlayId: JuggOverlayId, logger: ILogger) {
        val storeStartTime = System.currentTimeMillis()

        // store to memory
        val key = String.format("%s:%s", deviceSerial, packageName)
        memoryCache[key] = AsDeployerCompat.createDeploymentCacheEntry(newFiles, overlayId)

        // persist to db asynchronously
        postWithLock {
            logger.info("JuggDeploymentService.storeEntry, start")
            deploymentCacheStore.store(deviceSerial, packageName, newFiles.toCacheEntry(overlayId))
            logger.info("JuggDeploymentService.storeEntry, end, costTime: ${System.currentTimeMillis() - storeStartTime}ms")
        }
    }

    override fun loadCachedOverlayId(deviceSerial: String, packageName: String, logger: Logger): CachedOverlayId? {
        return loadEntry(deviceSerial, packageName, AdbLogWrapper(logger))
            ?.overlayId
            ?.let { CachedOverlayId(sha = it.sha, isBaseInstall = it.isBaseInstall) }
    }

    override fun loadEntry(deviceSerial: String, packageName: String, logger: ILogger): JuggDeploymentCacheEntry? {
        // load from memory
        val memCache = memoryCache[String.format("%s:%s", deviceSerial, packageName)]
        if (memCache != null) {
            logger.info("JuggDeploymentService.loadEntry, load from memory cache")
            return memCache
        }

        // load from db
        val dbResult = withLock { deploymentCacheStore.load(deviceSerial, packageName) }
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


    private fun <T> withLock(block: JuggDeploymentService.() -> T): T {
        synchronized(lock) {
            return JuggDeploymentService.block()
        }
    }

    private fun postWithLock(block: JuggDeploymentService.() -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(lock) {
                JuggDeploymentService.block()
            }
        }
    }
}
