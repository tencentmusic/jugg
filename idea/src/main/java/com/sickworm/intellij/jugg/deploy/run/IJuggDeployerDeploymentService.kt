package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.OverlayId
import com.android.tools.deployer.model.Apk
import com.android.utils.ILogger
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService

/**
 * Deployment cache access used by [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer].
 */
interface IJuggDeployerDeploymentService : IJuggDeploymentService {
    fun loadEntry(deviceSerial: String, packageName: String, logger: ILogger): DeploymentCacheDatabase.Entry?

    fun storeEntry(
        deviceSerial: String,
        packageName: String,
        newFiles: List<Apk>,
        overlayId: OverlayId,
        logger: ILogger,
    )
}
