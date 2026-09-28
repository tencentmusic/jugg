package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.android.tools.deployer.DexComparator.ChangedClasses
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import java.util.zip.CRC32

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
        val overlayFiles = linkedMapOf<String, Pair<ApkEntry, ByteString>>()
        (data.overlays + data.nativeLibraryOverlays.filterNot { it.isFileBacked }).forEach { item ->
            val targetPaths = item.targetApkPaths.ifEmpty { listOf(item.apkPath) }
            targetPaths.forEach { targetPath ->
                val scopedApk = cacheEntryMap[targetPath]
                val apk = when {
                    targetPath == DeployItem.FLAG_CLASS || targetPath == DeployItem.FLAG_BASE_APK -> baseApk
                    scopedApk != null -> scopedApk
                    item.type == CompileOutput.Type.NativeLib ->
                        throw IllegalArgumentException("Unknown APK scope for ${item.name}: $targetPath")
                    else -> baseApk
                }
                val overlay = item.toIncompleteOverlay(apk)
                overlayFiles.putIfAbsent(overlay.first.qualifiedPath, overlay)
            }
        }

        val bigNativeFiles = linkedMapOf<String, Long>()
        data.nativeLibraryOverlays.filter { it.isFileBacked }.forEach { item ->
            item.targetApkPaths.ifEmpty { listOf(item.apkPath) }.forEach { targetPath ->
                val apk = cacheEntryMap[targetPath]
                    ?: throw IllegalArgumentException("Unknown APK scope for ${item.name}: $targetPath")
                bigNativeFiles.putIfAbsent("${apk.name}/${item.name}", item.checksum)
            }
        }
        if (bigNativeFiles.isNotEmpty()) {
            // Seed the marker with the previous checkpoint so later native updates cannot reuse an older ID.
            val content = (listOf(cacheEntry.overlayId.sha) +
                bigNativeFiles.toSortedMap().map { (path, checksum) -> "$path:$checksum" })
                .joinToString("\n").toByteArray(Charsets.UTF_8)
            val entry = ApkEntry(BIG_SO_CHECKSUM_OVERLAY, CRC32().apply { update(content) }.value, baseApk)
            require(overlayFiles.putIfAbsent(entry.qualifiedPath, entry to ByteString.copyFrom(content)) == null) {
                "Reserved overlay path: ${entry.qualifiedPath}"
            }
        }

        return asDeployerCompat.createOverlayUpdate(cacheEntry, dexOverlays, overlayFiles.values.associate { it })
    }

    private companion object {
        const val BIG_SO_CHECKSUM_OVERLAY = ".jugg_big_so_checksum"
    }
}
