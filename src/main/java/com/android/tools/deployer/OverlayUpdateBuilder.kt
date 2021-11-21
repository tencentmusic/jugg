package com.android.tools.deployer

import com.android.tools.deployer.DexComparator.ChangedClasses
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString
import com.sickworm.intellij.jugg.project.JuggException

class OverlayUpdateBuilder {

    fun build(cacheEntry: DeploymentCacheDatabase.Entry?, data: JuggDeployData): OverlayUpdate {
        if (data.apks.size > 1 && data.overlays.isNotEmpty()) {
            throw JuggException.notSupportMultiApkOverlays()
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

        return OverlayUpdate(cacheEntry, dexOverlays, overlayFiles)
    }

    fun convert(overlayUpdate: JuggOverlayUpdate): OverlayUpdate {
        return overlayUpdate.overlayUpdate
    }
}

/**
 * [OverlayUpdate] fields are all private, so we need this.
 */
data class JuggOverlayUpdate(
    val cachedDump: DeploymentCacheDatabase.Entry?,
    val dexOverlays: ChangedClasses,
    val fileOverlays: Map<ApkEntry, ByteString>
) {
    val overlayUpdate = OverlayUpdate(cachedDump, dexOverlays, fileOverlays)
}