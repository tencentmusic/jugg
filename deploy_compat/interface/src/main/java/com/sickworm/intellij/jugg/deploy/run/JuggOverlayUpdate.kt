package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.OptimisticApkSwapper
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString

/**
 * [OptimisticApkSwapper.OverlayUpdate] fields are all private, so we need this.
 */
data class JuggOverlayUpdate(
    val cachedDump: DeploymentCacheDatabase.Entry,
    val dexOverlays: DexComparator.ChangedClasses,
    val fileOverlays: Map<ApkEntry, ByteString>
) {
    val overlayUpdate = OptimisticApkSwapper.OverlayUpdate(cachedDump, dexOverlays, fileOverlays)
}