package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.sickworm.intellij.jugg.deploy.run.LaunchContext

/**
 * DirectOverlaySwapTransport replaces the Apply Changes overlay-update transport only.
 * It does not own deploy lifecycle decisions; callers still run through JuggDeployTask and JuggDeployerHelper.
 */
class DirectOverlaySwapTransport(
    private val launchContext: LaunchContext,
    private val logger: Logger,
) {

    fun trySwap(
        packageName: String,
        data: JuggDeployData,
        overlayUpdate: JuggOverlayUpdate,
        asDeployerCompat: IAsDeployerCompat,
        appArch: Deploy.Arch = Deploy.Arch.ARCH_64_BIT,
    ): JuggOverlayId? {
        return try {
            trySwapInternal(packageName, data, overlayUpdate, asDeployerCompat, appArch)
        } catch (e: Exception) {
            if (e is DirectOverlayDirtyException) throw e
            if (e is DirectOverlayDeployFailedException) throw e
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            logger.debug("Direct overlay swap failed, fallback to Apply Changes.", e)
            null
        }
    }

    fun canTry(data: JuggDeployData): Boolean {
        launchContext.logDirectOverlayEnabled(logger)
        val result = launchContext.isDirectOverlayEnabled && !data.isInstall && !data.isEmpty
        logger.debug(
            "Direct overlay swap canTry=$result: isInstall=${data.isInstall}, isEmpty=${data.isEmpty}",
        )
        return result
    }

    private fun trySwapInternal(
        packageName: String,
        data: JuggDeployData,
        overlayUpdate: JuggOverlayUpdate,
        asDeployerCompat: IAsDeployerCompat,
        appArch: Deploy.Arch,
    ): JuggOverlayId? {
        if (!canTry(data)) {
            return null
        }

        val adb = launchContext.deviceAdb
        val deviceAbi = launchContext.deviceAbi
        ensureApplyChangesStartupAgent(packageName, adb, deviceAbi, appArch)

        val expectedDeviceOverlayId = overlayUpdate.cachedDump.overlayId.let {
            if (it.isBaseInstall) "" else it.sha
        }
        val state = DirectOverlayStateChecker(adb, logger).checkDevice(packageName, expectedDeviceOverlayId)
        if (state != DirectOverlayStateCheckResult.MATCHED) {
            logger.debug("Direct overlay swap skipped for device overlay state: $state")
            return null
        }

        val preparedRequest = DirectOverlayWriteRequestBuilder(logger).build(
            packageName,
            overlayUpdate,
            asDeployerCompat,
            data.isFullRes,
        )
        when (DirectOverlayWriter(adb, logger).write(preparedRequest.request)) {
            DirectOverlayWriteResult.SUCCESS -> return preparedRequest.overlayId
            DirectOverlayWriteResult.SKIPPED -> {
                logger.debug("Direct overlay swap skipped for writer failure before overlay mutation.")
                return null
            }
            DirectOverlayWriteResult.FAILED_DIRTY -> {
                throw DirectOverlayDirtyException("Direct overlay writer failed after mutating overlay directory.")
            }
        }
    }

    private fun ensureApplyChangesStartupAgent(
        packageName: String,
        adb: IDeviceAdb,
        deviceAbi: String,
        appArch: Deploy.Arch,
    ) {
        val installerVersion = launchContext.installSession.installerVersion
        if (installerVersion == null) {
            logger.debug("Direct overlay startup agent push skipped for missing installer metadata.")
            return
        }
        AsStartupAgentPusher(
            adb = adb,
            matryoshkaReader = InstallerMatryoshkaReader(launchContext.installersRoot, logger),
            versionHash = installerVersion,
            logger = logger,
        ).pushApplyChangesStartupAgent(packageName, deviceAbi, appArch)
    }
}

class DirectOverlayDirtyException(message: String) : RuntimeException(message)
