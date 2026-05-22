package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deployer.OverlayId
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManagerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate

/**
 * DirectOverlaySwapTransport replaces the Apply Changes overlay-update transport only.
 * It does not own deploy lifecycle decisions; callers still run through JuggDeployTask and JuggDeployerHelper.
 */
class DirectOverlaySwapTransport(
    private val options: DirectOverlaySwapOptions,
    private val logger: Logger,
) {

    fun trySwap(
        packageName: String,
        data: JuggDeployData,
        overlayUpdate: JuggOverlayUpdate,
    ): OverlayId? {
        return try {
            trySwapInternal(packageName, data, overlayUpdate)
        } catch (e: Exception) {
            if (e is DirectOverlayDirtyException) throw e
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            logger.debug("Direct overlay swap failed, fallback to Apply Changes.", e)
            null
        }
    }

    fun canTry(data: JuggDeployData): Boolean {
        val result = options.enabled &&
                !options.isDeviceReadyDeploy &&
                !data.isInstall &&
                !data.isEmpty
        logger.debug("Direct overlay swap canTry=$result. Details: enabled: ${options.enabled}, " +
                "isDeviceReadyDeploy: ${options.isDeviceReadyDeploy}, isInstall: ${data.isInstall}, isEmpty: ${data.isEmpty}")
        return result
    }

    private fun trySwapInternal(
        packageName: String,
        data: JuggDeployData,
        overlayUpdate: JuggOverlayUpdate,
    ): OverlayId? {
        if (overlayUpdate.cachedDump.overlayId.isBaseInstall) {
            logger.debug("Direct overlay swap skipped for base install cache.")
            return null
        }

        val adb = options.adb ?: run {
            logger.debug("Direct overlay swap skipped for adb not ready.")
            return null
        }

        val expectedDeviceOverlayId = overlayUpdate.cachedDump.overlayId.let {
            if (it.isBaseInstall) "" else it.sha
        }
        val state = DirectOverlayStateChecker(adb, logger).checkDevice(packageName, expectedDeviceOverlayId)
        if (state != DirectOverlayStateCheckResult.MATCHED) {
            logger.debug("Direct overlay swap skipped for device overlay state: $state")
            return null
        }

        val preparedRequest = DirectOverlayWriteRequestBuilder().build(packageName, overlayUpdate)
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
}

class DirectOverlayDirtyException(message: String) : RuntimeException(message)

/**
 * DirectOverlaySwapOptions carries deploy-state facts from the outer lifecycle into the swap transport.
 */
data class DirectOverlaySwapOptions(
    val enabled: Boolean,
    val isDeviceReadyDeploy: Boolean,
    val adb: IDeviceAdb?,
) {
    companion object {
        fun disabled(): DirectOverlaySwapOptions {
            return DirectOverlaySwapOptions(
                enabled = false,
                isDeviceReadyDeploy = true,
                adb = null,
            )
        }
    }
}
