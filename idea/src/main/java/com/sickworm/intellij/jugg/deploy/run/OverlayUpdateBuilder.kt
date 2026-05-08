package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DeployerException
import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.DexComparator.ChangedClasses

class OverlayUpdateBuilder {

    fun build(cacheEntry: DeploymentCacheDatabase.Entry?, data: JuggDeployData): JuggOverlayUpdate {

        if (cacheEntry == null) {
            throw DeployerException.remoteApkNotFound()
        }

        val newClasses = (data.newClasses + data.hotFixModifiedClasses).map {
            it.toIncompleteDexClass()
        }
        val modifiedClasses = data.hotReloadModifiedClasses.map {
            it.toIncompleteDexClass()
        }
        val dexOverlays = ChangedClasses(newClasses, modifiedClasses)

        val baseApk = cacheEntry.apks.find { it.name == "base.apk" } ?: cacheEntry.apks.first()
        val cacheEntryMap = cacheEntry.apks.associateBy { it.path }
        val overlayFiles = data.overlays.flatMap { item ->
            val targetPaths = item.targetApkPaths.ifEmpty { listOf(item.apkPath) }
            targetPaths.map { targetPath ->
                val apk = if (targetPath == DeployItem.FLAG_CLASS || targetPath == DeployItem.FLAG_BASE_APK) {
                    baseApk
                } else {
                    cacheEntryMap[targetPath] ?: baseApk
                }
                item.toIncompleteOverlay(apk)
            }
        }.associate { it }

        return JuggOverlayUpdate(cacheEntry, dexOverlays, overlayFiles)
    }
}
