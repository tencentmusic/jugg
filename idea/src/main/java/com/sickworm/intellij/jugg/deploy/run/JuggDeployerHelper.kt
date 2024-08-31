package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.IdeInfo
import com.android.tools.idea.run.ApkInfo
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.ide.JuggRunningTask
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
    private val dependencyChangeManager: IDependencyChangeManager,
    private val compileContextManager: CompileContextManager,
    private val juggServer: JuggServer,
    private val coroutineScope: CoroutineScope,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployerHelper"),
    private var installPathProvider: Computable<String> = Computable<String> {
        CopyEmbeddedDistributionPaths().get()
    },
) {

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

        if (androidDeployType == AndroidDeployType.INSTALL) {
            // stop first, avoid confusing by user why App is stopped after installed later
            deployTargetManager.stopApp(device)
        }

        val detectJob = coroutineScope.async {
            val adb = IdeaDeviceAdb(device, logger)
            JuggJvmtiAgentManagerHelper(logger).isNeedPushAgentAfterDeploy(adb, data)
        }

        if (!data.isInstall && dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            removeLibraryDexFiles(data, device)
        }

        val task = JuggDeployTask(project, installPathProvider, androidDeployType, data)

        val launchContext = LaunchContext(device, deployHistoryManager.lastDeployOverlayIds, isSkipExceptOverlayCheck)
        val launchResult = task.run(launchContext)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        var isNeedPushAgentAfterDeploy: Boolean
        runBlocking {
            isNeedPushAgentAfterDeploy = detectJob.await()
            logger.debug("isNeedPushAgentAfterDeploy: $isNeedPushAgentAfterDeploy")
            if (isNeedPushAgentAfterDeploy) {
                val adb = IdeaDeviceAdb(device, logger)
                JuggJvmtiAgentManagerHelper(logger).pushAgentToApps(adb, data)
            }
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

        if (isNeedPushAgentAfterDeploy) {
            // check JVMTI compatibility issue
            // waiting app foreground (which means JVMTI agent boot finished)
            if (!deployTargetManager.isAppForeground(device)) {
                waitingForDeployable(device)
            }
            val adb = IdeaDeviceAdb(device, logger)
            val isHasJvmtiCompatIssue = JuggJvmtiAgentManagerHelper(logger).isHasJvmtiCompatIssue(adb, data)
            if (isHasJvmtiCompatIssue) {
                throw IllegalStateException(REDEPLOY_WITH_COMPAT_MESSAGE)
            }
        }

        logger.debug("runTask end")
        isRunning = false

        return launchResult
    }

    private fun removeLibraryDexFiles(data: JuggDeployData, device: IDevice) {
        val removedDexFilesByVersionRollback = dependencyChangeManager.getRemovedLibraryFiles()
            .filter { it.type == CompileFile.Type.Class }
            .map(ChangedFile::jarDexFileName)
            .toSet()
        logger.debug("removedDexFilesByVersionRollback: $removedDexFilesByVersionRollback")

        val deployLibraryDexFiles = (data.hotReloadModifiedClasses + data.hotFixModifiedClasses)
            .filter { it.isLibraryDex }
            .map { it.name + ".dex" }
            .toSet()
        val deployedDexFiles = compileContextManager.compileContext.deployedFiles
            .filter { it.file.name.endsWith(".dex") }
            .map { it.file.name }
            .toSet()
        val removedDexFilesByClassRollback = dependencyChangeManager.getNewLibraryFiles()
            .filter { it.type == CompileFile.Type.Class }
            .map { it.jarDexFileName }
            // find out library dex that is deployed before but has no changes with full compile
            .filter { deployedDexFiles.contains(it) && !deployLibraryDexFiles.contains(it) }
            .toSet()
        logger.debug("deployLibraryDexFiles: $deployLibraryDexFiles")
        logger.debug("deployedDexFiles: $deployedDexFiles")
        logger.debug("removedDexFilesByClassRollback: $removedDexFilesByClassRollback")

        val removedDexFiles = removedDexFilesByVersionRollback + removedDexFilesByClassRollback
        if (removedDexFiles.isNotEmpty()) {
            TimeLogger.start("remove dex")
            logger.info("Before deploy, need to delete reverted libraries dex:\n" +
                    removedDexFiles.joinToString("\n    ", prefix = "    "))
            removedDexFiles.forEach { dexFileName ->
                data.apks.forEach {
                    val packageName = it.applicationId
                    logger.debug("delete $packageName - $dexFileName")
                    try {
                        AdbCmdHelper(device, logger).deleteDeployedDexFile(packageName, dexFileName)
                    } catch (e: Exception) {
                        logger.debug("delete $packageName - $dexFileName failed", e)
                        logger.warn("delete $packageName - $dexFileName failed, reason:\n$e")
                    }
                }
            }
            logger.info("Delete removed libraries dex finished.")
            TimeLogger.end("remove dex", logger)
        }
    }

    @Suppress("KotlinConstantConditions")
    fun deploy(device: IDevice,
               isLastDevice: Boolean,
               processHandler: ProcessHandler? = null,
               indicator: ProgressIndicator? = null,
               isInstall: Boolean = false,
               isWarmUp: Boolean = false,
               retryReason: String? = null,
               isSkipExceptOverlayCheck: Boolean = false,
               retryDeployData: JuggDeployData? = null,
               startTime: Long = System.currentTimeMillis(),
    ): DeployTaskResult {

        fun costTime(): Long { return System.currentTimeMillis() - startTime }

        if (processHandler != null && (processHandler.isProcessTerminating || processHandler.isProcessTerminated)) {
            logger.warn("Deploy canceled.")
            return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "deploy canceled")
        }

        logger.debug("Deploying... isInstall: $isInstall, isWarmUp: $isWarmUp")

        val deployState = deployStateManager.updateDeployState()
        logger.debug("Jugg deploy state: $deployState")

        var finalIsFallbackAllHotFix = false
        var deployData: JuggDeployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        return try {
            if (isInstall) {
                val apks = deployTargetManager.getApks()
                logger.info("Installing APK... ${apks.firstOrNull()?.files?.first()?.apkFile}")
                deployData = JuggDeployData.forInstall(apks)
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

                deployData = retryDeployData ?: deployFileManager.getDeployData(isWarmUp, isNeedPushResourceApk(device, deployData))
                var isNeedReinstallApk = false
                val isRetry = retryReason != null // retry means we have already resigned the apk
                if (deployData.isNeedUpdateApk && !isRetry) {
                    logger.info("Need resign APK to update files: ${deployData.updateApkFiles}.")
                    logger.info("Resigning APK...")
                    TimeLogger.start("insertFileAndResignApk")
                    val (isSuccess, failedReason) = insertFileAndResignApk(
                        deployData.apks, compileContextManager.compileContext, deployData.updateApkFiles)
                    if (!isSuccess) {
                        return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = failedReason)
                    }
                    logger.info("Resign APK file finished, cost ${TimeLogger.getCostTime("insertFileAndResignApk")}ms.")
                    isNeedReinstallApk = true
                }

                var isRecoverWithReinstall = false
                if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy) {
                    if (deployStateManager.getDeployState(device).isReadyIncCompile) {
                        val (isSuccess, isReinstalled) = recoverDeployState(device, indicator,
                            isNeedTryDeyDeployFirst = !isNeedReinstallApk,
                            isInstallUpdateApk = isNeedReinstallApk,
                            isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
                        )
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

                // get deploy data again after resigning apk (trigger full res deploy)
                if (isRecoverWithReinstall) {
                    deployData = deployFileManager.getDeployData(isWarmUp, isNeedPushResourceApk(device, deployData))
                }

                val isClassNeedHotFix = deployData.hotFixModifiedClasses.isNotEmpty() ||
                        dependencyChangeManager.getRemovedLibraryFiles().any { it.type == CompileFile.Type.Class }
                finalIsFallbackAllHotFix = JuggSettings.isQuickFallbackToHotFix && isClassNeedHotFix
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

                juggServer.report {
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
                val isInstrumentationFailed = reason.contains("INSTRUMENTATION_FAILED") || reason.contains("IOException occurred")
                // unknown reason, just fallback is enough to fix
                val isInternalError = reason.contains("JVMTI_ERROR_INTERNAL")
                // self exception, deploy with compat mode
                val isRedeployWithCompatMode = reason.contains(REDEPLOY_WITH_COMPAT_MESSAGE)

                if (isRedeployWithCompatMode) {
                    logger.warn(REDEPLOY_WITH_COMPAT_MESSAGE)
                    val nextRetryDeployData = deployFileManager.appendCompatDeployFiles(deployData)
                    return deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, retryDeployData = nextRetryDeployData, startTime = startTime, isSkipExceptOverlayCheck = true)
                }

                val nextRetryDeployData = deployData.toFallbackToHotFixData().copy(isPushOverlayOnly = true)

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
                    return deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, retryDeployData = nextRetryDeployData, startTime = startTime, isSkipExceptOverlayCheck = true)
                }

                val isAgentNotResponses = reason.contains("MISSING_AGENT_RESPONSES") || reason.contains("AGENT_ATTACH_FAILED")
                if (isAgentNotResponses) {
                    logger.info("Deploy agent no response, push files directly and restart.")
                    return deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, retryDeployData = nextRetryDeployData, startTime = startTime, isSkipExceptOverlayCheck = true)
                }

                val isOverlayIdNotCorrect = reason.contains("OVERLAY_ID_MISMATCH") || reason.contains("unable to recognize the APK")
                val isClassNotFoundException = reason.contains("Class not found")
                // logical error in JuggDeployer, thrown by DeployerException.overlayIdMismatch()
                val isOverlayIdNotMatch = reason.contains("The target app on the device is in a state unknown to Studio")

                if (isOverlayIdNotCorrect || isClassNotFoundException || isOverlayIdNotMatch) {
                    val (isNeedRecover, isNeedTryDeyDeployFirst) = when {
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
                    if (isNeedRecover) {
                        val (isSuccess, _) = recoverDeployState(device, indicator, isNeedTryDeyDeployFirst, isSkipExceptOverlayCheck)
                        val result: DeployTaskResult = if (!isSuccess) {
                            logger.info("Try recover deploy state failed on retry.")
                            DeployTaskResult(isSuccess = false, costTime = costTime(),
                                failedReason = "Try recover deploy state failed on retry.")
                        } else {
                            logger.info("Try recover deploy state success on retry.")
                            juggServer.report {
                                action = "incremental_deploy_retry_after_recover"
                                detail = reason
                            }
                            deploy(device, isLastDevice, processHandler, isInstall = false, isWarmUp = isWarmUp, retryReason = reason, startTime = startTime, isSkipExceptOverlayCheck = true)
                        }
                        return result.copy(costTime = costTime())
                    }
                }
            }

            if (isInstall) {
                logger.warn("Install APK failed. Reason: $reason")
                logger.debug(e)
            } else {
                logger.warn("Deploy Changes failed. Reason: $reason")
                logger.debug(e)
            }

            DeployTaskResult(isSuccess = false, deployType = deployData.deployType, isCanFallback = !isInstall, costTime = costTime(), failedReason = reason)
        }
    }

    private fun updateInfoAfterIncDeploy(launchResult: LaunchResult, deployData: JuggDeployData) {
        val deployedFiles = deployFileManager.getStagingFiles()
        deployHistoryManager.updateHistoryOnAfterDeployed(deployedFiles)
        deployFileManager.commit(deployData)
        deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
    }

    /**
     * Redeploy apk and compiled files.
     * Will check deploy state on device first. If matched, won't reinstall apk and redeploy compiled files.
     * @return <isSuccess, isReinstalled>
     */
    private fun recoverDeployState(device: IDevice, indicator: ProgressIndicator?,
                                   isNeedTryDeyDeployFirst: Boolean,
                                   isSkipExceptOverlayCheck: Boolean,
                                   isInstallUpdateApk: Boolean = false,
    ): Pair<Boolean, Boolean> {
        if (!isInstallUpdateApk) {
            logger.info("App not ready to deploy, recover deploy state from history.")
        }

        // dry deploy first, if success, no need to reinstall and recover
        if (isNeedTryDeyDeployFirst) {
            val isSuccess = tryDryDeploy(device, isSkipExceptOverlayCheck)
            if (isSuccess) {
                logger.info("Deploy state matched, no need reinstall app.")
                return true to false
            } else {
                logger.warn("Deploy state not match, start reinstalling app...")
                indicator?.text = "Reinstalling app..."
                JuggRunningTask.notifyByBalloon(project, "Deploy state not match, start reinstalling app...")
            }
        } else if (isInstallUpdateApk) {
            logger.info("App updated, start reinstalling app...")
            indicator?.text = "Installing app..."
            JuggRunningTask.notifyByBalloon(project, "App updated, start reinstalling app...")
        } else {
            logger.warn("Deploy state not match, start reinstalling app...")
            indicator?.text = "Reinstalling app..."
            JuggRunningTask.notifyByBalloon(project, "Deploy state not match, start reinstalling app...")
        }

        // recover deploy state for device
        val deployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        logger.debug("going to install apks: ${deployData.apks.flatMap { it.files }.map { it.apkFile }}")

        val costTime = measureTimeMillis {
            runTask(device, deployData)
        }
        logger.info("Reinstalling app finished, cost ${costTime}ms.")
        deployFileManager.resetAfterReinstall()

        return true to true
    }

    /**
     * @return isSuccess
     */
    private fun tryDryDeploy(device: IDevice, isSkipExceptOverlayCheck: Boolean): Boolean {
        logger.info("Start app and waiting app deployable.")
        if (!deployTargetManager.restartApp(device)) {
            logger.debug("Try start app failed")
            return false
        }

        logger.info("Device online, try dry deploy.")
        return try {
            val dryDeployData = JuggDeployData.forDryDeploy(deployTargetManager.getApks())
            runTask(device, dryDeployData, isSkipExceptOverlayCheck = isSkipExceptOverlayCheck)
            true
        } catch (e: Exception) {
            val reason = e.message ?: e.cause?.message ?: "null"
            if (reason.contains(REDEPLOY_WITH_COMPAT_MESSAGE)) {
                logger.debug("ignore \"$REDEPLOY_WITH_COMPAT_MESSAGE\" on dry deploy, will handle it later")
                return true
            }
            logger.debug("Dry deploy failed, reason: $reason")
            false
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

    /**
     * @return <isSuccess, failedReason>
     */
    @Suppress("LiftReturnOrAssignment")
    private fun insertFileAndResignApk(apkInfos: List<ApkInfo>, compileContext: ICompileContext, files: List<DeployItem>): Pair<Boolean, String> {
        if (apkInfos.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        if (apkInfos.first().files.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        val apkFile = apkInfos.first().files.first().apkFile
        val signingConfig = compileContext.signingConfig
        if (signingConfig == null || signingConfig.isInvalid) {
            logger.warn("Unable to update APK, signing config not found.")
            return false to "AndroidManifest.xml changed and signing config not found"
        }
        val modifier = ApkFileModifier(apkFile, signingConfig, compileContext.androidHome, logger.getInstance("ApkFileModifier"))
        try {
            files.forEach {
                modifier.addFile(it.name, it.content)
            }
            modifier.insertAndResign()
            return true to ""
        } catch (e: Exception) {
            logger.debug("unexpected error when insert file and resign apk", e)
            logger.warn("Insert file and resign apk failed, reason: $e")
            return false to "rewrite APK failed"
        }
    }

    private fun isNeedPushResourceApk(device: IDevice, data: JuggDeployData): Boolean {
        val isEnableCompatDeploy = CompatDeployHelper(logger).isEnableCompatDeploy(IdeaDeviceAdb(device, logger), data)
        logger.debug("isNeedPushResourceApk: " +
                "isEnableCompatDeploy: $isEnableCompatDeploy, " +
                "finalIsEnableCompatibleDeploymentMode: ${JuggSettings.finalIsEnableCompatibleDeploymentMode}, " +
                "device api: ${device.version.apiLevel}"
        )
        if (!JuggSettings.finalIsEnableCompatibleDeploymentMode) {
            // Compat deploy mode not enabled, unable to use this function.
            return false
        }
        if (isEnableCompatDeploy) {
            return true
        }
        return false
    }

    companion object {
        private val runTaskLock = Object()

        const val DO_NOT_RETRY = "DO_NOT_RETRY"
        private const val REDEPLOY_WITH_COMPAT_MESSAGE = "Detect JVMTI compatibility issue, need to fallback to compat deploy."
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