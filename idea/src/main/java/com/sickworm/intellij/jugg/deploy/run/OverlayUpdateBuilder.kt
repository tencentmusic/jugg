package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DexComparator.ChangedClasses

class OverlayUpdateBuilder(private val asDeployerCompat: IAsDeployerCompat) {

    fun build(cacheEntry: JuggDeploymentCacheEntry?, data: JuggDeployData): JuggOverlayUpdate {

        if (cacheEntry == null) {
            throw asDeployerCompat.remoteApkNotFound()
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
        val overlayFiles = data.overlays.associate {
            val apk = if (it.apkPath == DeployItem.FLAG_CLASS || it.apkPath == DeployItem.FLAG_BASE_APK) {
                baseApk
            } else {
                cacheEntryMap[it.apkPath] ?: baseApk
            }
            it.toIncompleteOverlay(apk)
        }

        return asDeployerCompat.createOverlayUpdate(cacheEntry, dexOverlays, overlayFiles)
    }
}
