package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DeployerException
import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.DexComparator.ChangedClasses
import com.sickworm.intellij.jugg.project.JuggException

class OverlayUpdateBuilder {

    fun build(cacheEntry: DeploymentCacheDatabase.Entry?, data: JuggDeployData): JuggOverlayUpdate {
        if (data.apks.size > 1 && data.overlays.isNotEmpty()) {
            throw JuggException.notSupportMultiApk()
        }

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

        val overlayFiles = data.overlays.associate {
            it.toIncompleteOverlay(cacheEntry.apks.first())
        }

        return JuggOverlayUpdate(cacheEntry, dexOverlays, overlayFiles)
    }

}

