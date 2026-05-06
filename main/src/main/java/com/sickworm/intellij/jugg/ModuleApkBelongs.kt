package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.project.data.ModuleInfo

/**
 * ModuleApkBelongs stores module-to-APK ownership with a primary APK view and optional extra APK targets.
 */
class ModuleApkBelongs(
    private val primaryApkMap: Map<ModuleInfo, ApkFileUnit>,
    private val allApkMap: Map<ModuleInfo, List<ApkFileUnit>>,
) {

    fun getBelongsApk(moduleInfo: ModuleInfo): ApkFileUnit? {
        return primaryApkMap[moduleInfo]
    }

    fun getAllBelongsApk(moduleInfo: ModuleInfo): List<ApkFileUnit> {
        return allApkMap[moduleInfo] ?: emptyList()
    }

    operator fun get(moduleInfo: ModuleInfo): ApkFileUnit? {
        return getBelongsApk(moduleInfo)
    }

    fun entries(): Set<Map.Entry<ModuleInfo, ApkFileUnit>> {
        return primaryApkMap.entries
    }

    fun values(): Collection<ApkFileUnit> {
        return primaryApkMap.values
    }

    fun asMap(): Map<ModuleInfo, ApkFileUnit> {
        return primaryApkMap
    }
}
