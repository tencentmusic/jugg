package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.deploy.api.DexComparator.ChangedClasses
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.IApplyChangesExecutor
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate

class OverlayUpdateBuilder(private val applyChangesExecutor: IApplyChangesExecutor) {

    fun build(cacheEntry: JuggDeploymentCacheEntry?, data: JuggDeployData): JuggOverlayUpdate {

        if (cacheEntry == null) {
            throw applyChangesExecutor.remoteApkNotFound()
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
        val overlayFiles = linkedMapOf<String, Pair<ApkEntry, ByteString>>()
        data.overlays.forEach { item ->
            val targetPaths = item.targetApkPaths.ifEmpty { listOf(item.apkPath) }
            targetPaths.forEach { targetPath ->
                val apk = if (targetPath == DeployItem.Companion.FLAG_CLASS || targetPath == DeployItem.Companion.FLAG_BASE_APK) {
                    baseApk
                } else {
                    cacheEntryMap[targetPath] ?: baseApk
                }
                val overlay = item.toIncompleteOverlay(apk)
                overlayFiles.putIfAbsent(overlay.first.qualifiedPath, overlay)
            }
        }

        return applyChangesExecutor.createOverlayUpdate(cacheEntry, dexOverlays, overlayFiles.values.associate { it })
    }
}
