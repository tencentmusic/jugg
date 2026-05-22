package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.OverlayId
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
 * * optimize DeploymentCacheDatabase size
 * * provides synchronized and async api
 * * provides preInit
 */
object JuggDeploymentService : IJuggDeploymentService, IJuggDeployerDeploymentService {

    private val lock = Object()
    val deploymentCacheDbFile = JuggGlobalPathManager.deployCacheDbFile

    private val deploymentCacheDatabase: DeploymentCacheDatabase by lazy {
        DeploymentCacheDatabase(
            4,
            deploymentCacheDbFile,
        )
    }

    private var memoryCache: ConcurrentHashMap<String, DeploymentCacheDatabase.Entry> = ConcurrentHashMap()

    fun preInit(logger: Logger) {
        postWithLock {
            val costTime = measureTimeMillis {
                deploymentCacheDatabase
            }
            logger.debug("JuggDeploymentService.preInit, cost ${costTime}ms")
        }
    }

    override fun storeEntry(deviceSerial: String, packageName: String, newFiles: List<Apk>, overlayId: OverlayId, logger: ILogger) {
        val storeStartTime = System.currentTimeMillis()

        // store to memory
        val key = String.format("%s:%s", deviceSerial, packageName)
        memoryCache[key] = createEntry(newFiles, overlayId)

        // persist to db asynchronously
        postWithLock {
            logger.info("JuggDeploymentService.storeEntry, start")
            deploymentCacheDatabase.store(deviceSerial, packageName, newFiles, overlayId)
            logger.info("JuggDeploymentService.storeEntry, end, costTime: ${System.currentTimeMillis() - storeStartTime}ms")
        }
    }

    override fun loadCachedOverlayId(deviceSerial: String, packageName: String, logger: Logger): CachedOverlayId? {
        return loadEntry(deviceSerial, packageName, AdbLogWrapper(logger))
            ?.overlayId
            ?.let { CachedOverlayId(sha = it.sha, isBaseInstall = it.isBaseInstall) }
    }

    override fun loadEntry(deviceSerial: String, packageName: String, logger: ILogger): DeploymentCacheDatabase.Entry? {
        // load from memory
        val memCache = memoryCache[String.format("%s:%s", deviceSerial, packageName)]
        if (memCache != null) {
            logger.info("JuggDeploymentService.loadEntry, load from memory cache")
            return memCache
        }

        // load from db
        val dbResult = withLock { deploymentCacheDatabase[deviceSerial, packageName] }
        logger.info("JuggDeploymentService.loadEntry, load from db, result: $dbResult")
        return dbResult
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

    private fun createEntry(newInstalledApks: List<Apk>, overlayId: OverlayId): DeploymentCacheDatabase.Entry {
        val clazz = DeploymentCacheDatabase.Entry::class.java
        val constructor = clazz.getDeclaredConstructor(java.util.List::class.java, OverlayId::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(newInstalledApks, overlayId)
    }
}
