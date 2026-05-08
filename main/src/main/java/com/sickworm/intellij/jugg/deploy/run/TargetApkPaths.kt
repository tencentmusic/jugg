package com.sickworm.intellij.jugg.deploy.run

/**
 * Normalizes the target APK list so a concrete apkPath is always included when present.
 */
fun normalizeTargetApkPaths(apkPath: String?, targetApkPaths: List<String>): List<String> {
    return when (apkPath) {
        null, DeployItem.FLAG_CLASS, DeployItem.FLAG_BASE_APK -> targetApkPaths
        else -> (targetApkPaths + apkPath).distinct()
    }
}
