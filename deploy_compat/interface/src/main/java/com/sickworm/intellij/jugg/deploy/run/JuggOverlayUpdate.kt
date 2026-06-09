package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString

/**
 * Version-neutral holder for an OptimisticApkSwapper overlay update.
 */
data class JuggOverlayUpdate(
    val cachedDump: JuggDeploymentCacheEntry,
    val dexOverlays: DexComparator.ChangedClasses,
    val fileOverlays: Map<ApkEntry, ByteString>,
    val raw: Any,
)
