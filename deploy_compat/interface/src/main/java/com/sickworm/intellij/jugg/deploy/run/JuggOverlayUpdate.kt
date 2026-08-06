package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.deploy.api.DexComparator

/**
 * Version-neutral holder for an OptimisticApkSwapper overlay update.
 */
data class JuggOverlayUpdate(
    val cachedDump: JuggDeploymentCacheEntry,
    val dexOverlays: DexComparator.ChangedClasses,
    val fileOverlays: Map<ApkEntry, ByteString>,
    val raw: Any,
)
