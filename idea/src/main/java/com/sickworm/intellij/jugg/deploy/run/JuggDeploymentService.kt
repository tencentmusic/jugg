package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.SqlApkFileDatabase
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Paths

/**
 * copied from [com.android.tools.idea.run.DeploymentService]
 * * optimize DeploymentCacheDatabase size
 * * provides synchronized and async api
 * * provides preInit
 */
object JuggDeploymentService {

    private val lock = Object()

    val dexDatabase: SqlApkFileDatabase by lazy {
        // absolute path e.g.
        val dexDbPath = Paths.get(PathManager.getSystemPath(), ".dex_cache.db")
        SqlApkFileDatabase(
            dexDbPath.toFile(),
            PathManager.getTempPath()
        )
    }
    val deploymentCacheDatabase: DeploymentCacheDatabase by lazy {
        val deployDbPath = Paths.get(PathManager.getSystemPath(), ".deploy_cache.db")
        DeploymentCacheDatabase(
            4,
            deployDbPath.toFile(),
        )
    }

    fun preInit() {
        dexDatabase
        deploymentCacheDatabase
    }

    fun <T> withLock(block: JuggDeploymentService.() -> T): T {
        synchronized(lock) {
            return JuggDeploymentService.block()
        }
    }

    fun postWithLock(block: JuggDeploymentService.() -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(lock) {
                JuggDeploymentService.block()
            }
        }
    }
}