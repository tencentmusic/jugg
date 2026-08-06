package com.sickworm.intellij.jugg.deploy.run.flow

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.DeployProgress
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import kotlin.system.measureTimeMillis

/**
 * Recovers device deploy state via dry deploy or full reinstall when history and device diverge.
 */
open class DeployStateRecover(
    private val deployTargetManager: IDeployTargetManager,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployStateManager: IDeployStateManager,
    private val deployRunHost: IJuggDeployHelperRunHost,
    private val deploymentService: IJuggDeploymentService,
    private val environment: IDeployHost,
    private val logger: Logger,
) {

    /**
     * Redeploy apk and compiled files.
     * Will check deploy state on device first. If matched, won't reinstall apk and redeploy compiled files.
     * @return <isSuccess, isReinstalled>
     */
    open fun recoverDeployState(
        device: IDevice,
        progress: DeployProgress?,
        isNeedDryDeployFirst: Boolean, // which means device state is unknown and needs to detect first
        isSkipExceptOverlayCheck: Boolean,
        isInstallUpdateApk: Boolean = false,
        compileUiHandler: CompileUiHandler,
        allowDirectOverlayRecover: Boolean = true,
    ): Pair<Boolean, Boolean> {
        val isCleanAndReinstall = deployHistoryManager.isCleanAndReinstall
        val reinstallTips = if (isCleanAndReinstall) {
            "User triggered, start clean and reinstalling app..."
        } else {
            "Deploy state not match, start reinstalling app..."
        }

        if (isCleanAndReinstall) {
            progress?.update("Cleaning app...")
            val packageName = deployTargetManager.getPackageName()
            environment.clearAppData(device, packageName, logger)
        }

        // dry deploy first, if success, no need to reinstall and recover
        if (isNeedDryDeployFirst && !isCleanAndReinstall) {
            val dryDeployResult = tryDryDeploy(
                device,
                isSkipExceptOverlayCheck,
                compileUiHandler = compileUiHandler,
                allowDirectOverlayRecover = allowDirectOverlayRecover,
            )
            if (dryDeployResult == DryDeployResult.SUCCESS) {
                logger.debug("Deploy state matched, no need reinstall app.")
                return true to false
            } else {
                val tips = when (dryDeployResult) {
                    DryDeployResult.APP_NOT_INSTALLED -> "App not installed, start reinstalling app..."
                    DryDeployResult.SUCCESS -> error("unreachable")
                    DryDeployResult.FAILED -> reinstallTips
                }
                logger.warn(tips)
                progress?.update("Reinstalling app...")
                environment.notify(tips)
            }
        } else if (isInstallUpdateApk) {
            logger.info("App updated, start reinstalling app...")
            progress?.update("Installing app...")
            environment.notify("App updated, start reinstalling app...")
        } else {
            logger.warn(reinstallTips)
            progress?.update("Reinstalling app...")
            environment.notify(reinstallTips)
        }

        // recover deploy state for device
        val deployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        logger.debug("going to install apks: ${deployData.apks.flatMap { it.files }.map { it.apkFile }}")

        val deferPostDeployLaunch = allowDirectOverlayRecover && environment.isDirectOverlayEnabled
        val costTime = measureTimeMillis {
            deployRunHost.runRecoverDeployTask(
                device,
                deployData,
                isSkipExceptOverlayCheck = false,
                compileUiHandler = compileUiHandler,
                deferPostDeployLaunch = deferPostDeployLaunch,
                isAllowDirectOverlayDeploy = allowDirectOverlayRecover,
            )
        }
        logger.info("Reinstalling app finished, cost ${costTime}ms.")

        // Direct overlay recover defers launch; legacy recover must wait for the app to come online.
        if (deferPostDeployLaunch) {
            logger.debug("Skip wait for online; follow-up deploy will launch the app.")
        } else {
            val isDeviceDeployable = waitingForDeployable(device, maxWaitTimeSecond = 5)
            if (!isDeviceDeployable) {
                logger.warn("App not deployable after reinstalling.")
                return false to false
            }
        }

        deployFileManager.resetAfterReinstall()
        return true to true
    }

    open fun tryDryDeploy(
        device: IDevice,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
        allowDirectOverlayRecover: Boolean = true,
    ): DryDeployResult {
        if (deployTargetManager.isAppInstalled(device) == false) {
            return DryDeployResult.APP_NOT_INSTALLED
        }

        tryDirectDryDeploy(device, allowDirectOverlayRecover, isSkipExceptOverlayCheck)?.let {
            logger.debug("Try directly dry deploy finish, result: $it")
            return it
        }

        logger.info("Start app and waiting app deployable.")
        if (!deployTargetManager.restartApp(device)) {
            logger.debug("Try start app failed")
            return DryDeployResult.FAILED
        }

        val isDeviceDeployable = waitingForDeployable(device)
        if (!isDeviceDeployable) {
            logger.info("Dry deploy failed for app not launched.")
            return DryDeployResult.FAILED
        }

        logger.info("Device online, try dry deploy.")
        return try {
            val dryDeployData = JuggDeployData.Companion.forDryDeploy(deployTargetManager.getApks())
            deployRunHost.runRecoverDeployTask(
                device,
                dryDeployData,
                isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
                compileUiHandler = compileUiHandler,
                deferPostDeployLaunch = false,
                isAllowDirectOverlayDeploy = allowDirectOverlayRecover,
            )
            DryDeployResult.SUCCESS
        } catch (e: Exception) {
            val reason = e.message ?: e.cause?.message ?: "null"
            if (reason.contains(REDEPLOY_WITH_COMPAT_MESSAGE)) {
                logger.debug("ignore \"$REDEPLOY_WITH_COMPAT_MESSAGE\" on dry deploy, will handle it later")
                return DryDeployResult.SUCCESS
            }
            logger.debug("Dry deploy failed, reason: $reason")
            DryDeployResult.FAILED
        }
    }

    private fun tryDirectDryDeploy(
        device: IDevice,
        allowDirectOverlayRecover: Boolean,
        isSkipExceptOverlayCheck: Boolean,
    ): DryDeployResult? {
        if (!allowDirectOverlayRecover || !environment.isDirectOverlayEnabled) {
            logger.debug("Direct overlay state check skipped because direct overlay recover is disabled.")
            return null
        }
        val packageName = runCatching { deployTargetManager.getPackageName() }.getOrNull()
        if (packageName == null) {
            logger.info("Direct overlay state check skipped for missing package name.")
            return null
        }
        val result = DirectOverlayStateChecker(
            adb = environment.createDeviceAdb(device, logger),
            logger = logger,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
        ).checkRecover(device.serialNumber, packageName, isSkipExceptOverlayCheck)
        return when (result) {
            DirectOverlayStateCheckResult.MATCHED -> {
                logger.debug("Direct overlay state check matched, skip dry deploy.")
                DryDeployResult.SUCCESS
            }
            DirectOverlayStateCheckResult.MISMATCHED -> {
                logger.debug("Direct overlay state check mismatched, recover deploy state directly.")
                DryDeployResult.FAILED
            }
            DirectOverlayStateCheckResult.UNKNOWN -> {
                logger.debug("Direct overlay state check unknown, fallback to dry deploy.")
                null
            }
        }
    }

    private fun waitingForDeployable(device: IDevice, maxWaitTimeSecond: Int = 3): Boolean {
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
        private const val REDEPLOY_WITH_COMPAT_MESSAGE = "Detect JVMTI compatibility issue, need to fallback to compat deploy."
    }
}

enum class DryDeployResult {
    SUCCESS,
    APP_NOT_INSTALLED,
    FAILED,
}
