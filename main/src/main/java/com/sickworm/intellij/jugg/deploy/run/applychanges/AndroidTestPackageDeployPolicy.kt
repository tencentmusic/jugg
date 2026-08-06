package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData

/**
 * Decides how app-style androidTest APK packages participate in a deploy package loop.
 */
object AndroidTestPackageDeployPolicy {

    fun decide(
        apkInfos: List<ApkInfo>,
        scopedData: JuggDeployData,
        requestedType: AndroidDeployType,
    ): PackageDeployDecision {
        val isOtherTargeting = apkInfos.isNotEmpty() && apkInfos.all { it.isOtherTargetingTestApk }
        if (!isOtherTargeting || requestedType == AndroidDeployType.INSTALL) {
            return PackageDeployDecision(skip = false, effectiveType = requestedType, warningMessage = null)
        }
        val targets = apkInfos.mapNotNull { it.instrumentationTargetPackage }.distinct()
        val warning = "Skip non-INSTALL deploy for other-targeting androidTest APK: " +
            "targets=$targets, scopedIsEmpty=${scopedData.isEmpty}"
        return PackageDeployDecision(skip = true, effectiveType = requestedType, warningMessage = warning)
    }

    data class PackageDeployDecision(
        val skip: Boolean,
        val effectiveType: AndroidDeployType,
        val warningMessage: String?,
    )
}
