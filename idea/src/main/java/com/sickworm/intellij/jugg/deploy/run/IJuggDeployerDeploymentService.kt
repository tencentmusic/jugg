package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService

/**
 * Deployment cache access used by [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer].
 */
interface IJuggDeployerDeploymentService : IJuggDeploymentService {
    fun loadEntry(deviceSerial: String, packageName: String, logger: ILogger): JuggDeploymentCacheEntry?

    fun storeEntry(
        deviceSerial: String,
        packageName: String,
        newFiles: List<Apk>,
        overlayId: JuggOverlayId,
        logger: ILogger,
    )
}
