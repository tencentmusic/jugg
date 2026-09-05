package com.sickworm.intellij.jugg.deploy.run.flow

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.IncrementalDeployHelper
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.SliceDeployHelper
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOffline
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.server.JuggServer

/**
 * Decides how to retry incremental deploy after recoverable failures.
 */
class DeployRetryHandler(
    private val deployTargetManager: IDeployTargetManager,
    private val deployFileManager: DeployFileManager,
    private val deployStateRecover: DeployStateRecover,
    private val juggServer: JuggServer,
    private val deployRunHost: IJuggDeployHelperRunHost,
    private val environment: IDeployHost,
    private val logger: Logger,
    private val adbTransportRecovery: IAdbTransportRecovery = AdbTransportRecovery(environment, logger),
) {

    fun tryRetry(
        deployOptions: DeployOptions,
        finalIsFallbackAllHotFix: Boolean,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? {
        if (isOutOfMemoryReason(reason)) {
            logger.warn("Deploy stopped after an out of memory failure. Restart Android Studio, " +
                    "increase the IDE heap, or run a Gradle install before retrying Jugg.")
            return null
        }
        if (AdbTransientOffline.isOfflineMessage(reason)) {
            return tryRetryAfterDeviceOffline(deployOptions, deployData, reason)
        }

        val isAppForeground = deployTargetManager.isAppForeground(deployOptions.device)
        logger.debug("got exception: \"$reason\", isAppForeground: $isAppForeground")

        // e.g. change default method implementation of an interface class.
        // through we can detect it in some way, but it's more simple and good enough to fall back to HOT_FIX.
        val isUnmodifiableClass = reason.contains("JVMTI_ERROR_UNMODIFIABLE_CLASS")
        // something wrong with DeployDataGenerator... fall back too
        val isRequiresAppRestart = reason.contains("app restart")
        // seems like a bug of some devices e.g. OPPO Reno.
        val isRedifinerError = reason.contains("R+ Device should have FULL debugger swap support")
        // seems like a bug of deploy service, just retry
        val isInstrumentationFailed = reason.contains("INSTRUMENTATION_FAILED") || reason.contains("IOException occurred")
        // unknown reason, just fallback is enough to fix
        val isInternalError = reason.contains("JVMTI_ERROR_INTERNAL")
        // self exception, deploy with compat mode
        val isRedeployWithCompatMode = reason.contains(REDEPLOY_WITH_COMPAT_MESSAGE)

        if (isRedeployWithCompatMode) {
            logger.warn(REDEPLOY_WITH_COMPAT_MESSAGE)
            val nextRetryDeployData = deployFileManager.appendCompatDeployFiles(deployData)
            val nextDeployOptions = deployOptions.copy(retryReason = reason, retryDeployData = nextRetryDeployData, isSkipExceptOverlayCheck = true)
            return deployRunHost.redeploy(nextDeployOptions)
        }

        var nextRetryDeployData = deployData.toFallbackToHotFixData()

        val isClassModifiedError = (!finalIsFallbackAllHotFix) && (isUnmodifiableClass || isRequiresAppRestart || isRedifinerError || isInternalError)
        if (isClassModifiedError || isInstrumentationFailed) {
            if (isInstrumentationFailed) {
                logger.info("Deploy got INSTRUMENTATION_FAILED error, will retry again.")
            } else {
                logger.info("Deploy got hot reload error, will fallback to HOT_FIX.")
            }
            juggServer.report {
                action = "incremental_deploy_retry"
                detail = reason
            }
            val nextDeployOptions = deployOptions.copy(retryReason = reason, retryDeployData = nextRetryDeployData, isSkipExceptOverlayCheck = true)
            return deployRunHost.redeploy(nextDeployOptions)
        }

        val isAgentNotResponses = reason.contains("MISSING_AGENT_RESPONSES") || reason.contains("AGENT_ATTACH_FAILED")
        // got: "MessagePipeWrapper read() timeout (5000ms)" and throw by JuggDeployer.optimisticSwap
        val isDeployTimeout = reason.contains("MessagePipeWrapper read() timeout")
        val isJvmtiDeployStuck = isAgentNotResponses || isDeployTimeout

        val isOverlayIdNotCorrect = reason.contains("OVERLAY_ID_MISMATCH") || reason.contains("unable to recognize the APK")
        val isClassNotFoundException = reason.contains("Class not found")
        // logical error in JuggDeployer, thrown by DeployerException.overlayIdMismatch()
        val isOverlayIdNotMatch = reason.contains("The target app on the device is in a state unknown to Studio")
        val isDirectDeployFailed = reason.contains("Direct overlay")

        val reinstallWhenTimeout = deployOptions.timeOutRetryTimes == 2 // try to reinstall apk at the third time
        val stopRetryWhenTimeout = deployOptions.timeOutRetryTimes >= 3
        if (isJvmtiDeployStuck && stopRetryWhenTimeout) {
            logger.warn("Deploy timeout, retry times: ${deployOptions.timeOutRetryTimes}, stop retry.")
            return null
        }

        if (isAgentNotResponses && deployOptions.isAllowDirectOverlayDeploy
            && !deployOptions.forceDirectOverlayDeploy
            && deployOptions.timeOutRetryTimes == 0) {
            logger.info("Deploy agent no response, retry once with direct overlay.")
            val nextDeployOptions = deployOptions.copy(
                retryReason = JVMTI_STUCK_RETRY_REASON,
                isSkipExceptOverlayCheck = true,
                forceDirectOverlayDeploy = true,
                timeOutRetryTimes = deployOptions.timeOutRetryTimes + 1,
            )
            return deployRunHost.redeploy(nextDeployOptions)
        }

        if (isOverlayIdNotCorrect || isClassNotFoundException || isOverlayIdNotMatch || isJvmtiDeployStuck || isDirectDeployFailed) {
            var isRetryDirectly = false
            @Suppress("KotlinConstantConditions")
            when {
                isJvmtiDeployStuck -> {
                    val isNeedReduce = deployData.overlays.size >= JuggSettings.overlayDeploySplitSizeFirstSlice
                    if (isNeedReduce) {
                        logger.warn("Got deploy timeout exception, reduce overlay and retry")
                        environment.onDeployTimeout(deployOptions.device, logger)
                    } else {
                        if (reinstallWhenTimeout) {
                            logger.warn("Got deploy timeout exception, retry the last time with reinstalling APK.")
                            isRetryDirectly = false
                        } else {
                            logger.warn("Got deploy timeout exception, retry after 5s.")
                            Thread.sleep(5_000)
                            isRetryDirectly = true
                        }
                    }
                }
                isOverlayIdNotCorrect -> {
                    logger.info("Deploy history mismatch with the device, try recover deploy state.")
                }
                isDirectDeployFailed -> {
                    logger.info("Got direct deploy failed exception, try recover deploy state normal way.")
                }
                isClassNotFoundException -> {
                    logger.info("Got class not found exception, which means the deploy history mismatch with the device. Try recover deploy state.")
                }
                isOverlayIdNotMatch -> {
                    logger.info("The device's deploy status mismatch with this project, try recover deploy state.")
                }
            }
            if (!isRetryDirectly) {
                val isNeedDryDeployFirst = isDirectDeployFailed
                val allowDirectOverlayRecover = !isDirectDeployFailed && deployOptions.isAllowDirectOverlayDeploy
                val (isSuccess, _) = deployStateRecover.recoverDeployState(
                    deployOptions.device,
                    deployOptions.progress,
                    isNeedDryDeployFirst = isNeedDryDeployFirst,
                    deployOptions.isSkipExceptOverlayCheck,
                    compileUiHandler = deployOptions.compileUiHandler,
                    allowDirectOverlayRecover = allowDirectOverlayRecover,
                )
                if (!isSuccess) {
                    logger.warn("Try recover deploy state failed on retry.")
                    return DeployTaskResult(
                        isSuccess = false,
                        costTime = System.currentTimeMillis() - deployOptions.startTime,
                        failedReason = "Try recover deploy state failed on retry.",
                    )
                } else {
                    logger.debug("Try recover deploy state success on retry.")
                }
            }
            val nextDeployOptions = deployOptions.copy(
                retryReason = if (isJvmtiDeployStuck) JVMTI_STUCK_RETRY_REASON else reason,
                isSkipExceptOverlayCheck = true,
                isAllowDirectOverlayDeploy = !isDirectDeployFailed && deployOptions.isAllowDirectOverlayDeploy,
                forceDirectOverlayDeploy = false,
                timeOutRetryTimes = deployOptions.timeOutRetryTimes + if (isJvmtiDeployStuck) 1 else 0,
            )
            return deployRunHost.redeploy(nextDeployOptions)
        }

        deployRunHost.tryRetryInstall(deployOptions, deployData, reason)?.let {
            return it
        }

        return null
    }

    private fun tryRetryAfterDeviceOffline(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? {
        val device = deployOptions.device
        logger.info("Deploy got device offline, wait for device to recover.")
        if (!adbTransportRecovery.waitUntilRecovered(device, "deploy retry") { logger.info(it) }) {
            logger.warn(
                "Device ${device.serialNumber} still offline after ${AdbTransientOffline.DEFAULT_WAIT_MILLIS}ms, stop retry.",
            )
            return null
        }
        logger.info("Device ${device.serialNumber} recovered, retry deploy.")
        juggServer.report {
            action = "incremental_deploy_retry"
            detail = reason
        }
        val nextDeployOptions = deployOptions.copy(
            retryReason = reason,
            retryDeployData = deployData,
            isSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck,
        )
        return deployRunHost.redeploy(nextDeployOptions)
    }

    fun isCanFallbackOnException(reason: String, isInstall: Boolean): Boolean {
        // in this case, just stop deploy and waiting user trigger again
        val isUserRestrict = reason.contains("INSTALL_FAILED_USER_RESTRICT")
        // in this case, device may disconnect suddenly
        val isDeviceNotFound = reason.contains("device") && reason.contains("not found")
        // in this case, device is lost connection or version downgrade something
        val isApkInstallFailed = reason.contains("The application could not be installed.")
        // in this case, base APK is an incremental-embedded APK. Because incremental-embedded APK will create .overlay folder
        val isEmbeddedApk = reason.contains("overlay has no readable id file")
        if (isEmbeddedApk) {
            logger.warn("\nCaution:")
            logger.warn("The base APK is an Jugg incremental embedded APK, will conflict with incremental deploy.")
            logger.warn("Please make sure your APK does not contains \"${IncrementalDeployHelper.INCREMENTAL_DATA_PATH}\" folder.")
        }

        val isNeedStopDeploy = isUserRestrict || isDeviceNotFound || isApkInstallFailed || isEmbeddedApk
        if (isNeedStopDeploy) {
            logger.warn("\nDeploy Stopped.")
        }
        return !isInstall && !isNeedStopDeploy
    }

    companion object {
        // This marker is intentionally stable and never includes the retry count.
        // JuggDeployerHelper compares retryReason with the next real exception reason;
        // retry convergence is controlled by timeOutRetryTimes instead.
        const val JVMTI_STUCK_RETRY_REASON = "__jugg_jvmti_stuck_retry__"
        const val REDEPLOY_WITH_COMPAT_MESSAGE = "Detect JVMTI compatibility issue, need to fallback to compat deploy."

        internal fun isOutOfMemoryReason(reason: String): Boolean {
            return reason.contains("OutOfMemoryError") ||
                reason.contains("Java heap space") ||
                reason.contains("GC overhead limit exceeded")
        }
    }
}
