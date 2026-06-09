package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger

/**
 * CachedOverlayId is the main-layer view of deployment cache overlay state.
 */
data class CachedOverlayId(
    val sha: String,
    val isBaseInstall: Boolean,
)

/**
 * IJuggDeploymentService exposes deployment cache reads for main-layer deploy logic.
 */
interface IJuggDeploymentService {
    fun loadCachedOverlayId(deviceSerial: String, packageName: String, logger: Logger): CachedOverlayId?
}
