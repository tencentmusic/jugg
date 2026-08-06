package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.IJuggDeployerDeploymentService
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayFile
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.mock.logger

/**
 * Seeds deployment cache, deploy history, and virtual device overlay ids without Mockito alignment.
 */
object DeployFlowOverlaySeed {

    fun packageName(): String = context.apkInfos.first().applicationId

    fun seedMatchedTriple(
        virtualDevice: VirtualDeployDevice,
        deploymentService: IJuggDeployerDeploymentService,
        deployHistoryManager: IDeployHistoryManager,
    ): String {
        val packageName = packageName()
        val parsedApks = parseDemoApks()
        val overlay = buildNonBaseOverlayId(parsedApks)
        deploymentService.storeEntry(
            deviceSerial = virtualDevice.serial,
            packageName = packageName,
            newFiles = parsedApks,
            overlayId = overlay,
            logger = AdbLogWrapper(logger),
        )
        deployHistoryManager.lastDeployOverlayIds = mapOf(packageName to overlay.sha)
        virtualDevice.writeOverlayId(overlay.sha)
        return overlay.sha
    }

    fun seedHistoryAndCacheOnly(
        deploymentService: IJuggDeployerDeploymentService,
        deployHistoryManager: IDeployHistoryManager,
        virtualDevice: VirtualDeployDevice,
    ): String {
        val packageName = packageName()
        val parsedApks = parseDemoApks()
        val overlay = buildNonBaseOverlayId(parsedApks)
        deploymentService.storeEntry(
            deviceSerial = virtualDevice.serial,
            packageName = packageName,
            newFiles = parsedApks,
            overlayId = overlay,
            logger = AdbLogWrapper(logger),
        )
        deployHistoryManager.lastDeployOverlayIds = mapOf(packageName to overlay.sha)
        return overlay.sha
    }

    fun writeMismatchedDeviceOverlay(virtualDevice: VirtualDeployDevice, deviceOverlayId: String) {
        virtualDevice.writeOverlayId(deviceOverlayId)
    }

    fun realignDeviceAfterInstall(virtualDevice: VirtualDeployDevice, overlayId: String) {
        virtualDevice.writeOverlayId(overlayId)
    }

    /** Re-write deployment cache after mock install (install task may store a base-install overlay id). */
    fun restoreDeploymentCacheAfterMockInstall(
        virtualDevice: VirtualDeployDevice,
        deploymentService: IJuggDeploymentService,
        deployHistoryManager: IDeployHistoryManager,
    ) {
        val deployerService = deploymentService as IJuggDeployerDeploymentService
        seedHistoryAndCacheOnly(deployerService, deployHistoryManager, virtualDevice)
    }

    /**
     * Seeds base-install deployment cache and history without writing a device overlay id.
     * Used after mock reinstall when install stores a base-install overlay id.
     */
    fun seedBaseInstallHistoryAndCacheOnly(
        deploymentService: IJuggDeployerDeploymentService,
        deployHistoryManager: IDeployHistoryManager,
        virtualDevice: VirtualDeployDevice,
    ): String {
        val packageName = packageName()
        val parsedApks = parseDemoApks()
        val overlay = buildBaseInstallOverlayId(parsedApks)
        deploymentService.storeEntry(
            deviceSerial = virtualDevice.serial,
            packageName = packageName,
            newFiles = parsedApks,
            overlayId = overlay,
            logger = AdbLogWrapper(logger),
        )
        deployHistoryManager.lastDeployOverlayIds = mapOf(packageName to overlay.sha)
        return overlay.sha
    }

    /** Re-write base-install deployment cache after mock install (no device overlay dir). */
    fun restoreBaseInstallCacheAfterMockInstall(
        virtualDevice: VirtualDeployDevice,
        deploymentService: IJuggDeploymentService,
        deployHistoryManager: IDeployHistoryManager,
    ): String {
        val deployerService = deploymentService as IJuggDeployerDeploymentService
        return seedBaseInstallHistoryAndCacheOnly(deployerService, deployHistoryManager, virtualDevice)
    }

    private fun parseDemoApks(): List<Apk> {
        val apkPaths = context.apkInfos.flatMap { info -> info.files.map { it.apkFile.path } }
        return AsDeployerCompat.parseApks(apkPaths)
    }

    private fun buildNonBaseOverlayId(parsedApks: List<Apk>): JuggOverlayId {
        val base = AsDeployerCompat.createBaseOverlayId(parsedApks)
        val entryPath = parsedApks.first().name + "/res/layout/deploy_flow_seed.xml"
        return AsDeployerCompat.buildOverlayId(base, listOf(JuggOverlayFile(entryPath, 1L)))
    }

    private fun buildBaseInstallOverlayId(parsedApks: List<Apk>): JuggOverlayId {
        return AsDeployerCompat.createBaseOverlayId(parsedApks)
    }
}
