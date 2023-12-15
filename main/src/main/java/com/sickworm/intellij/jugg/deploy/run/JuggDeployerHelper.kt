package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.IdeInfo
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.ide.JuggStateListener
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.server.JuggReporter
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.android.download.AndroidProfilerDownloader
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployStateManager: DeployStateManager,
    private val juggReporter: JuggReporter,
    private val deployStateListenerGetter: () -> JuggStateListener,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployerHelper"),
    private var installPathProvider: Computable<String> = Computable<String> {
        CopyEmbeddedDistributionPaths().get()
    },
) {

    private val deployStateListener get() = deployStateListenerGetter.invoke()

    private var isRunning = false

    private fun runTask(device: IDevice, data: JuggDeployData, isSkipExceptOverlayCheck: Boolean = false): LaunchResult = synchronized(runTaskLock) {
        logger.debug("runTask start, isRunning: $isRunning")
        isRunning = true

        if (data.apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(data)
        }
        val androidDeployType = if (data.isInstall) {
            AndroidDeployType.INSTALL
        } else if (data.isNeedRestartActivity) {
            AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY
        } else {
            AndroidDeployType.APPLY_CHANGES
        }

        val task = JuggDeployTask(project, installPathProvider, androidDeployType, data)

        val consolePrinter = ConsolePrinter(logger)
        val launchContext = LaunchContext(consolePrinter, device, deployHistoryManager.lastDeployOverlayIds, isSkipExceptOverlayCheck)
        val launchResult = task.run(launchContext)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        if (data.isNeedRestartApp || androidDeployType == AndroidDeployType.INSTALL) {
            logger.debug("Restarting app...")
            deployTargetManager.restartApp(device)
        } else if (!deployTargetManager.isAppForeground(device)) {
            logger.debug("Starting app...")
            deployTargetManager.startApp(device)
        } else {
            logger.debug("App foreground, no need to restart app.")
        }

        logger.debug("runTask end")
        isRunning = false

        return launchResult
    }

    fun deploy(device: IDevice,
               isLastDevice: Boolean,
               processHandler: ProcessHandler? = null,
               isInstall: Boolean = false,
               isWarmUp: Boolean = false,
               retryReason: String? = null,
               isFallbackAllHotFix: Boolean = false,
               isSkipExceptOverlayCheck: Boolean = false,
               startTime: Long = System.currentTimeMillis(),
    ): DeployTaskResult {

        fun costTime(): Long { return System.currentTimeMillis() - startTime }

        if (processHandler != null && (processHandler.isProcessTerminating || processHandler.isProcessTerminated)) {
            logger.warn("Deploy canceled.")
            return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "deploy canceled")
        }

        logger.debug("Deploying... isInstall: $isInstall, isWarmUp: $isWarmUp, isFallbackAllHotFix: $isFallbackAllHotFix")

        val deployState = deployStateManager.updateDeployState()
        logger.debug("Jugg deploy state: $deployState")

        var finalIsFallbackAllHotFix = isFallbackAllHotFix
        return try {
            if (isInstall) {
                val apks = deployTargetManager.getApks()
                logger.info("Installing APK... ${apks.firstOrNull()?.files?.first()?.apkFile}")
                val deployData = JuggDeployData.forInstall(apks)
                val launchResult = runTask(device, deployData)
                if (isLastDevice) {
                    logger.debug("Installing finished, update info after install.")
                    deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
                }
                DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = deployData.deployType)
            } else {
                if (!deployTargetManager.hasDevice) {
                    logger.warn("\nNo device connected, please check device is connected.")
                    return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "no device connected")
                }

                if (isWarmUp && !deployStateManager.getDeployState(device).isReadyDeploy) {
                    logger.info("Device not ready to warm up.")
                    return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "device not ready to warm up")
                }

                var isRecoverWithReinstall = false
                if (!deployStateManager.getDeployState(device).isReadyDeploy) {
                    if (deployStateManager.getDeployState(device).isReadyIncCompile) {
                        val (isSuccess, isReinstalled) = recoverDeployState(device, isNeedTryDeyDeployFirst = true)
                        if (!isSuccess) {
                            logger.info("Try recover deploy state failed.")
                            return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = "Try recover deploy state failed.")
                        } else {
                            logger.info("Try recover deploy state success.")
                            isRecoverWithReinstall = isReinstalled
                        }
                    } else {
                        logger.warn("Invalid state for deploy.")
                        return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = "Invalid state for deploy.")
                    }
                }

                var deployData = deployFileManager.getDeployData(isWarmUp)
                finalIsFallbackAllHotFix = isFallbackAllHotFix ||
                        (JuggSettings.isQuickFallbackToHotFix && deployData.hotFixModifiedClasses.isNotEmpty())
                if (finalIsFallbackAllHotFix) {
                    deployData = deployData.toFallbackToHotFixData()
                }

                logger.debug("Deploying data(debug):\n$deployData")
                logger.info("Deploying data:\n${deployData.toDescString()}")
                if (deployData.isFullRes) {
                    logger.info("It's first time to push overlays(full push), it may takes more times to resolved.")
                }
                val finalIsSkipExceptOverlayCheck = isSkipExceptOverlayCheck || isRecoverWithReinstall
                val launchResult = runTask(device, deployData, finalIsSkipExceptOverlayCheck)

                deployStateListener.onDeployed(
                    false,
                    deployFileManager.getCompiledFiles().map { it.file },
                )
                if (isLastDevice) {
                    logger.debug("Deploying finished, update info after deploy.")
                    updateInfoAfterIncDeploy(launchResult, deployData)
                }

                DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = deployData.deployType)
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.cause?.message ?: "null"
            val canRetry = (retryReason != DO_NOT_RETRY) && (retryReason == null || retryReason != reason)
            if (canRetry && !isInstall) {
                val isAppForeground = deployTargetManager.isAppForeground(device)
                logger.debug("got exception: \"$reason\", isAppForeground: $isAppForeground")

                juggReporter.report {
                    action = "incremental_deploy_retry_start"
                    detail = reason
                }

                // e.g. change default method implementation of an interface class.
                // through we can detect it in some way, but it's more simple and good enough to fall back to HOT_FIX.
                val isUnmodifiableClass = reason.contains("JVMTI_ERROR_UNMODIFIABLE_CLASS")
                // something wrong with DeployDataGenerator... fall back too
                val isRequiresAppRestart = reason.contains("app restart")
                // seems like a bug of some devices e.g. OPPO Reno.
                val isRedifinerError = reason.contains("R+ Device should have FULL debugger swap support")
                // seems like a bug of deploy service, just retry
                val isInstrumentationFailed = reason.contains("INSTRUMENTATION_FAILED")
                if ((!finalIsFallbackAllHotFix) && (isUnmodifiableClass || isRequiresAppRestart || isRedifinerError || isInstrumentationFailed)) {
                    if (isInstrumentationFailed) {
                        logger.info("Deploy got INSTRUMENTATION_FAILED error, will retry again with HOT_FIX.")
                    } else {
                        logger.info("Deploy got hot reload error, will fallback to HOT_FIX.")
                    }
                    juggReporter.report {
                        action = "incremental_deploy_retry"
                        detail = reason
                    }
                    return deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, isFallbackAllHotFix = true, startTime = startTime, isSkipExceptOverlayCheck = true)
                }

                val isAgentNotResponses = reason.contains("MISSING_AGENT_RESPONSES") || reason.contains("AGENT_ATTACH_FAILED")
                val isOverlayIdNotCorrect = reason.contains("OVERLAY_ID_MISMATCH")
                val isClassNotFoundException = reason.contains("Class not found")
                // logical error in JuggDeployer, thrown by DeployerException.overlayIdMismatch()
                val isOverlayIdNotMatch = reason.contains("The target app on the device is in a state unknown to Studio")
                if (isAgentNotResponses || isOverlayIdNotCorrect || isClassNotFoundException || isOverlayIdNotMatch) {
                    val (isNeedRecover, isNeedTryDeyDeployFirst) = when {
                        isAgentNotResponses && !isAppForeground-> {
                            logger.info("Deploy agent no response, and App is not in foreground, try recover deploy state.")
                            true to true
                        }
                        isOverlayIdNotCorrect -> {
                            logger.info("Deploy history mismatch with the device, try recover deploy state.")
                            true to false
                        }
                        isClassNotFoundException -> {
                            logger.info("Got class not found exception, which means the deploy history mismatch with the device. Try recover deploy state.")
                            true to true
                        }
                        isOverlayIdNotMatch -> {
                            logger.info("The device's deploy status mismatch with this project, try recover deploy state.")
                            true to true
                        }
                        else -> false to false
                    }
                    val result: DeployTaskResult = if (isNeedRecover) {
                        val (isSuccess, _) = recoverDeployState(device, isNeedTryDeyDeployFirst)
                        if (!isSuccess) {
                            logger.info("Try recover deploy state failed on retry.")
                            DeployTaskResult(isSuccess = false, costTime = costTime(),
                                failedReason = "Try recover deploy state failed on retry.")
                        } else {
                            logger.info("Try recover deploy state success on retry.")
                            juggReporter.report {
                                action = "incremental_deploy_retry_after_recover"
                                detail = reason
                            }
                            deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, isFallbackAllHotFix = finalIsFallbackAllHotFix, startTime = startTime, isSkipExceptOverlayCheck = true)
                        }
                    } else {
                        val delaySeconds = 5
                        logger.info("Deploy agent no response, but App is in foreground, try again after ${delaySeconds}s.")
                        Thread.sleep(delaySeconds * 1000L)
                        juggReporter.report {
                            action = "incremental_deploy_retry"
                            detail = reason
                        }
                        deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, isFallbackAllHotFix = finalIsFallbackAllHotFix, startTime = startTime, isSkipExceptOverlayCheck = true)
                    }
                    return result.copy(costTime = costTime())
                }
            }

            if (isInstall) {
                logger.warn("Install APK failed. Reason: $reason")
                logger.debug(e)
            } else {
                logger.warn("Deploy Changes failed. Reason: $reason")
                logger.debug(e)
            }

            DeployTaskResult(isSuccess = false, isCanFallback = !isInstall, costTime = costTime(), failedReason = reason)
        }
    }

    private fun updateInfoAfterIncDeploy(launchResult: LaunchResult, deployData: JuggDeployData) {
        val compiledFiles = deployFileManager.getCompiledFiles()
        val deployedFiles = deployFileManager.getStagingFiles()
        deployHistoryManager.updateHistoryOnAfterDeployed(compiledFiles, deployedFiles)
        deployFileManager.commit(deployData)
        deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
    }

    /**
     * Redeploy apk and compiled files.
     * Will check deploy state on device first. If matched, won't reinstall apk and redeploy compiled files.
     * @return <isSuccess, isReinstalled>
     */
    private fun recoverDeployState(device: IDevice, isNeedTryDeyDeployFirst: Boolean): Pair<Boolean, Boolean> {
        logger.info("App not ready to deploy, recover deploy state from history.")

        // dry deploy first, if success, no need to reinstall and recover
        if (isNeedTryDeyDeployFirst) {
            val (isSuccess, isCanReinstall) = tryDryDeploy(device)
            if (isSuccess) {
                logger.info("Deploy state matched, no need reinstall app.")
                return true to false
            } else if (isCanReinstall) {
                logger.warn("Deploy state not match, start reinstalling app...")
            } else {
                logger.debug("Dry deploy failed and isCanReinstall=false, exit dry deploy.")
                return false to false
            }
        } else {
            logger.warn("Deploy state not match, start reinstalling app...")
        }

        // recover deploy state for device
        val deployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        val costTime = measureTimeMillis {
            runTask(device, deployData)
        }
        logger.info("Reinstalling app finished, cost ${costTime}ms.")
        deployFileManager.resetAfterReinstall()

        val isDeviceDeployable = waitingForDeployable(device)
        if (!isDeviceDeployable) {
            logger.warn("Recovery failed for app not launched.")
            return false to true
        }

        logger.info("Device online, continue deploy.")
        return true to true
    }

    /**
     * @return Pair<isSuccess, isCanReinstall>
     */
    private fun tryDryDeploy(device: IDevice, canRetry: Boolean = true, needStartApp: Boolean = true): Pair<Boolean, Boolean> {
        if (needStartApp) {
            logger.info("Start app and waiting app deployable.")
            if (!deployTargetManager.restartApp(device)) {
                logger.debug("Try start app failed")
                return false to false
            }
        }
        val isDeviceDeployable = waitingForDeployable(device)
        if (!isDeviceDeployable) {
            logger.info("Dry deploy failed for app not launched.")
            return false to true
        }

        logger.info("Device online, try dry deploy.")
        return try {
            val dryDeployData = JuggDeployData.forDryDeploy(deployTargetManager.getApks())
            runTask(device, dryDeployData)
            true to false
        } catch (e: Exception) {
            val reason = e.message ?: e.cause?.message ?: "null"
            val isNoResponse = reason.contains("MISSING_AGENT_RESPONSES")
            if (isNoResponse) {
                val isAppForeground = deployTargetManager.isAppForeground(device)
                logger.debug("got MISSING_AGENT_RESPONSES, canRetry: $canRetry, isAppForeground: $isAppForeground")
                if (canRetry && isAppForeground) {
                    val delaySeconds = 5
                    logger.info("Deploy agent no response, but App is in foreground, try dry deploy again after ${delaySeconds}s.")
                    Thread.sleep(delaySeconds * 1000L)
                    return tryDryDeploy(device, canRetry = false, needStartApp = false)
                } else {
                    false to false
                }
            }
            logger.debug("Dry deploy failed, reason: $reason")
            false to true
        }
    }

    private fun waitingForDeployable(device: IDevice): Boolean {
        val maxWaitTimeSecond = 3
        var waitedTimeSecond = 0
        val waitGapMillSecond = 1
        while (waitedTimeSecond < maxWaitTimeSecond) {
            Thread.sleep(waitGapMillSecond * 1000L)
            waitedTimeSecond += waitGapMillSecond
            logger.info("($waitedTimeSecond/$maxWaitTimeSecond) waiting app launched...")
            deployStateManager.updateDeployState()
            if (deployStateManager.getDeployState(device).isReadyDeploy) {
                return true
            }
        }

        logger.info("App not launched, please check the app is started and debuggable, and adb is not occupied by other process")
        return false
    }

    companion object {
        private val runTaskLock = Object()

        const val DO_NOT_RETRY = "DO_NOT_RETRY"
    }
}

/**
 * Copied from EmbeddedDistributionPaths.getInstance().findEmbeddedInstaller()
 * because this method only exists in Intellij Idea
 */
private class CopyEmbeddedDistributionPaths {

    fun get(): String {
        val path = "plugins/android/resources/installer"
        var file: File? = File(PathManager.getHomePath(), path)
        if (file!!.exists()) {
            return file.absolutePath
        }

        file = getOptionalIjPath(path)
        if (file != null && file.exists()) {
            return file.absolutePath
        }
        // Development mode
        assert(IdeInfo.getInstance().isAndroidStudio) { "Bazel paths exist only in AndroidStudio development mode" }
        return File(
            PathManager.getHomePath(),
            "../../bazel-bin/tools/base/deploy/installer/android-installer"
        ).absolutePath
    }

    private fun getOptionalIjPath(@Suppress("SameParameterValue") path: String): File? {
        // IJ does not bundle some large resources from android plugin, and downloads them on demand.
        AndroidProfilerDownloader.getInstance().makeSureComponentIsInPlace()
        return AndroidProfilerDownloader.getInstance().getHostDir(path)
    }
}

data class DeployTaskResult(
    val isSuccess: Boolean,
    val costTime: Long,
    val isCanFallback: Boolean = false,
    val deployType: JuggDeployData.DeployType? = null,
    val failedReason: String? = null,
)