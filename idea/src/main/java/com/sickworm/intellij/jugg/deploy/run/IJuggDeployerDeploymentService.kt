package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.model.Apk
import com.android.utils.ILogger
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService

/**
 * Deployment cache access used by [JuggDeployer].
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
