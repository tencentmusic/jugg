package com.sickworm.intellij.jugg.deploy.run

import android.annotation.SuppressLint
import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.IdeInfo
import com.android.tools.idea.log.LogWrapper
import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.IncrementalDeployHelper
import com.sickworm.intellij.jugg.compiler.jarDexFileName
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
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
    private val taskRunnerManager: TaskRunnerManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployerHelper"),
    private var installPathProvider: Computable<String> = Computable<String> {
        CopyEmbeddedDistributionPaths().get()
    },
) {

    private var isRunning = false

    private fun runTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean = false,
        compileUiHandler: CompileUiHandler,
    ): LaunchResult = synchronized(runTaskLock) {
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

        val detectJob = taskRunnerManager.runAsyncSafe("isNeedPushAgentAfterDeploy") {
            val adb = IdeaDeviceAdb(device, logger)
            JuggJvmtiAgentManagerHelper(logger).isNeedPushAgentAfterDeploy(adb, data)
        }

        if (!data.isInstall && dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            removeLibraryDexFiles(data, device)
        }

        val (firstSliceSize, sliceSize) = SliceDeployHelper(logger).get(IdeaDeviceAdb(device, logger))
        val dataList = data.splitData(firstSliceSize, sliceSize)
        logger.debug("deploy_to_device size: ${dataList.size}")

        TimeLogger.start("deploy_to_device")
        lateinit var launchResult: LaunchResult
        dataList.forEachIndexed { i, splitData ->
            if (dataList.size > 1) TimeLogger.start("deploy_to_device_slice$i")
            logger.debug("deploy_to_device_slice$i, " +
                    "classes: ${splitData.newClasses.size + splitData.hotFixModifiedClasses.size + splitData.hotReloadModifiedClasses.size}, " +
                    "overlays: ${splitData.overlays.size}")
            val isSliceSkipExceptOverlayCheck = isSkipExceptOverlayCheck || i != 0
            val launchContext = LaunchContext(
                device = device,
                exceptOverlayIds = deployHistoryManager.lastDeployOverlayIds,
                isSkipExceptOverlayCheck = isSliceSkipExceptOverlayCheck,
                compileUiHandler = compileUiHandler,
            )
            val task = JuggDeployTask(project, installPathProvider, androidDeployType, splitData)
            launchResult = task.run(launchContext)
            if (!launchResult.success) {
                throw JuggException.applyChangesFailed(launchResult)
            }
            if (dataList.size > 1) TimeLogger.end("deploy_to_device_slice$i", logger)
        }
        TimeLogger.end("deploy_to_device", logger)

        TimeLogger.start("push_agent")
        var isNeedPushAgentAfterDeploy: Boolean
        runBlocking {
            isNeedPushAgentAfterDeploy = detectJob.await() ?: false
            logger.debug("isNeedPushAgentAfterDeploy: $isNeedPushAgentAfterDeploy")
            if (isNeedPushAgentAfterDeploy) {
                val adb = IdeaDeviceAdb(device, logger)
                JuggJvmtiAgentManagerHelper(logger).pushAgentToApps(adb, data)
            }
        }
        launchResult.pushingAgentCostTime = TimeLogger.end("push_agent", logger)

        var isNeedRestartApp = data.isNeedRestartApp

        if (compileUiHandler.isAlwaysRestartApp && !isNeedRestartApp && !data.isEmpty) {
            logger.info("isAlwaysRestartApp=true: forcing app restart after deployment.")
            isNeedRestartApp = true
        }

        if (JuggSettings.isAlwaysRestartAppAfterDeployment) {
            logger.info("User require always restart app after deployment, restart app.")
            isNeedRestartApp = true
        }

        if ((isNeedPushAgentAfterDeploy && !isNeedRestartApp) || (data.isFullRes && !isNeedRestartApp)) {
            val adb = IdeaDeviceAdb(device, logger)
            if (PlatformApi.isHasRelaunchActivityIssues(adb, logger)) {
                // fix JVMTI compatibility issue for Android >=15 below Android Studio Meerkat
                // restart app to let fix works
                if (data.isFullRes) {
                    logger.info("Fix JVMTI compatibility issue for Android >=15 below Android Studio Meerkat at first time deploy res, restart app.")
                } else {
                    logger.info("Fix JVMTI compatibility issue for Android >=15 below Android Studio Meerkat at first time deploy, restart app.")
                }
                isNeedRestartApp = true
            }
        }

        if (isNeedRestartApp || androidDeployType == AndroidDeployType.INSTALL) {
            logger.debug("Restarting app...")
            deployTargetManager.restartApp(device)
        } else if (!deployTargetManager.isAppForeground(device)) {
            logger.debug("Starting app...")
            deployTargetManager.startApp(device)
        } else {
            logger.debug("App foreground, no need to restart app.")
        }

        TimeLogger.start("check_jvmti")

        if (isNeedPushAgentAfterDeploy && isNeedRestartApp) {
            // check JVMTI compatibility issue
            // waiting app foreground (which means JVMTI agent boot finished)
            val adb = IdeaDeviceAdb(device, logger)
            val isHasJvmtiCompatIssue = JuggJvmtiAgentManagerHelper(logger).isHasJvmtiCompatIssue(adb, data)
            if (isHasJvmtiCompatIssue && !data.isCompatDeploy) {
                juggServer.report {
                    action = "jvmti_compat_issue"
                    detail = Gson().toJson(mapOf(
                        "device" to adb.displayName,
                        "application" to data.apks.firstOrNull()?.applicationId,
                    ))
                }
                throw IllegalStateException(REDEPLOY_WITH_COMPAT_MESSAGE)
            }
        }
        launchResult.checkJvmtiCostTime = TimeLogger.end("check_jvmti", logger)

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

    fun deploy(deployOptions: DeployOptions): DeployTaskResult {
        logger.debug("deploy start, deployOptions: $deployOptions")
        val device = deployOptions.device
        fun costTime(): Long { return System.currentTimeMillis() - deployOptions.startTime }

        if (deployOptions.processHandler != null && (deployOptions.processHandler.isCanceled)) {
            logger.warn("Deploy canceled.")
            return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "deploy canceled")
        }

        logger.debug("Deploying... isInstall: ${deployOptions.isInstall}, isWarmUp: ${deployOptions.isWarmUp}")

        val deployState = deployStateManager.updateDeployState()
        logger.debug("Jugg deploy state: $deployState")

        var finalIsFallbackAllHotFix = false
        var deployData: JuggDeployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        return try {
            if (deployOptions.isInstall) {
                val apks = deployTargetManager.getApks()
                val apkFiles = apks.flatMap { it.files.map { it.apkFile } }
                if (apkFiles.size <= 1) {
                    logger.info("Installing APK... ${apkFiles.first()}")
                } else {
                    logger.info("Installing APK...\n${apkFiles.joinToString("\n")}")
                }
                deployData = JuggDeployData.forInstall(apks)
                val launchResult = runTask(deployOptions.device, deployData, compileUiHandler = deployOptions.compileUiHandler)
                if (deployOptions.isLastDevice) {
                    logger.debug("Installing finished, update info after install.")
                    deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
                }
                DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = deployData.deployType)
            } else if (JuggSettings.isEmbeddedToApk) {
                embeddedToApk(deployOptions)
            } else if (!compileContextManager.compileContext.isDebuggable) {
                logger.warn("APK is not debuggable, will auto switch to embedded to apk mode.")
                embeddedToApk(deployOptions)
            } else {
                if (!deployTargetManager.hasDevice) {
                    logger.warn("\nNo device connected, please check device is connected.")
                    return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "no device connected")
                }

                if (deployOptions.isWarmUp && !deployStateManager.getDeployState(device).isReadyDeploy) {
                    logger.info("Device not ready to warm up.")
                    return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "device not ready to warm up")
                }

                deployData = deployOptions.retryDeployData ?: deployFileManager.getDeployData(deployOptions.isWarmUp, isNeedPushResourceApk(device, deployData))

                var isNeedReinstallApk = false
                val isRetry = deployOptions.retryReason != null // retry means we have already resigned the apk
                if (deployData.isNeedUpdateApk && !isRetry) {
                    logger.info("Need resign APK to update files: ${deployData.updateApkFiles}.")
                    logger.info("Resigning APK...")
                    TimeLogger.start("insertFileAndResignApk")
                    val (isSuccess, failedReason) = IncrementalDeployHelper(compileContextManager.compileContext, logger)
                        .updateApk(deployData.apks, deployData.updateApkFiles)
                    if (!isSuccess) {
                        return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = failedReason)
                    }
                    logger.info("Resign APK file finished, cost ${TimeLogger.getCostTime("insertFileAndResignApk")}ms.\n")
                    isNeedReinstallApk = true
                }

                var isRecoverWithReinstall = false
                if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy) {
                    if (deployStateManager.getDeployState(device).isReadyIncCompile) {
                        val (isSuccess, isReinstalled) = recoverDeployState(
                            device, deployOptions.indicator,
                            isNeedTryDeyDeployFirst = !isNeedReinstallApk,
                            isInstallUpdateApk = isNeedReinstallApk,
                            isSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck,
                            compileUiHandler = deployOptions.compileUiHandler,
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
                    deployData = deployFileManager.getDeployData(deployOptions.isWarmUp, isNeedPushResourceApk(device, deployData))
                }

                val isClassNeedHotFix = deployData.hotFixModifiedClasses.isNotEmpty() ||
                        dependencyChangeManager.getRemovedLibraryFiles().any { it.type == CompileFile.Type.Class }
                finalIsFallbackAllHotFix = JuggSettings.isQuickFallbackToHotFix && isClassNeedHotFix
                if (finalIsFallbackAllHotFix) {
                    deployData = deployData.toFallbackToHotFixData()
                }

                logger.debug("Deploying data(debug):\n$deployData")
                logger.info("Deploying data:\n${deployData.toDescString()}")
                if (deployData.isFullRes && !deployData.isCompatDeploy) {
                    logger.info("It's first time to push overlays(full push), it may takes more times to resolved.")
                }
                val finalIsSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck || isRecoverWithReinstall
                val launchResult = runTask(device, deployData, finalIsSkipExceptOverlayCheck, compileUiHandler = deployOptions.compileUiHandler)

                if (deployOptions.isLastDevice) {
                    logger.debug("Deploying finished, update info after deploy.")
                    updateInfoAfterIncDeploy(launchResult, deployData)
                }

                DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = deployData.deployType, costTimeExceptCheck = costTime() - launchResult.checkJvmtiCostTime)
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.cause?.message ?: e.toString()
            val retryReason = deployOptions.retryReason
            val canRetry = (retryReason != DO_NOT_RETRY) && (retryReason == null || retryReason != reason)
            if (canRetry) {
                logger.debug("try retry deploy..., deployOptions: $deployOptions")
                if (deployOptions.isInstall) {
                    val retryResult = tryRetryInstall(deployOptions, deployData, reason)
                    if (retryResult != null) {
                        return retryResult
                    }
                } else {
                    val retryResult = tryRetry(deployOptions, finalIsFallbackAllHotFix, deployData, reason)
                    if (retryResult != null) {
                        return retryResult
                    }
                }
            }

            if (deployOptions.isInstall) {
                logger.warn("Install APK failed. Reason: $reason")
                logger.debug(e)
            } else {
                logger.warn("Deploy Changes failed. Reason: $reason")
                logger.debug(e)
            }

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
            val isCanFallback = !deployOptions.isInstall && !isNeedStopDeploy

            DeployTaskResult(isSuccess = false, deployType = deployData.deployType, isCanFallback = isCanFallback, costTime = costTime(), failedReason = reason)
        }
    }

    private fun embeddedToApk(deployOptions: DeployOptions): DeployTaskResult {
        val incDeployData = deployFileManager.getDeployData(deployOptions.isWarmUp, false)
        val apks = incDeployData.apks
        val apkFiles = apks.flatMap { it.files.map { it.apkFile } }
        if (apkFiles.size <= 1) {
            logger.info("Embedding APK... ${apkFiles.first()}")
        } else {
            logger.info("Embedding APK...\n${apkFiles.joinToString("\n")}")
        }
        val classes = (incDeployData.newClasses + incDeployData.hotFixModifiedClasses + incDeployData.hotReloadModifiedClasses)
        val deployItems = classes.map { it.deployItem } + incDeployData.overlays + incDeployData.updateApkFiles
        val deployedItems = deployFileManager.getDeployedFiles()
            .map { it.toDeployItem() }
            .filter { deployedItem ->
                !deployItems.any {
                    it.name == deployedItem.name
                }
            }
        val (isSuccess, failedReason) = IncrementalDeployHelper(compileContextManager.compileContext, logger).updateApk(
            incDeployData.apks, deployItems + deployedItems)
        logger.debug("Embedding APK finished, isSuccess: $isSuccess, failedReason: $failedReason")
        if (!isSuccess) {
            return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = deployOptions.costTime(), failedReason = failedReason)
        }

        val deployData = JuggDeployData.forInstall(apks)
        val launchResult = runTask(deployOptions.device, deployData, compileUiHandler = deployOptions.compileUiHandler)
        if (deployOptions.isLastDevice) {
            logger.debug("Installing finished, update info after install.")
            deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
            updateInfoAfterIncDeploy(launchResult, incDeployData)
        }
        return DeployTaskResult(isSuccess = true, costTime = deployOptions.costTime(), deployType = deployData.deployType)
    }

    private fun tryRetry(
        deployOptions: DeployOptions,
        finalIsFallbackAllHotFix: Boolean,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? {
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
            return deploy(nextDeployOptions)
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
            return deploy(nextDeployOptions)
        }

        val isAgentNotResponses = reason.contains("MISSING_AGENT_RESPONSES") || reason.contains("AGENT_ATTACH_FAILED")
        // got: "MessagePipeWrapper read() timeout (5000ms)" and throw by JuggDeployer.optimisticSwap
        val isDeployTimeout = reason.contains("MessagePipeWrapper read() timeout")
        if (isAgentNotResponses || isDeployTimeout) {
            logger.info("Deploy agent no response, going to detect JVMTI is available.")
            // try detect compat issues
            if (detectJvmtiCompatIssue(deployOptions.device, deployData)) {
                logger.warn("Detect JVMTI compat issue, fallback to compat deploy mode.")
                nextRetryDeployData = deployFileManager.appendCompatDeployFiles(deployData)
                val nextDeployOptions = deployOptions.copy(retryReason = reason, retryDeployData = nextRetryDeployData, isSkipExceptOverlayCheck = true)
                return deploy(nextDeployOptions)
            } else {
                logger.debug("JVMTI is available.") // detectJvmtiCompatIssue will log info
            }
        }

        val isOverlayIdNotCorrect = reason.contains("OVERLAY_ID_MISMATCH") || reason.contains("unable to recognize the APK")
        val isClassNotFoundException = reason.contains("Class not found")
        // logical error in JuggDeployer, thrown by DeployerException.overlayIdMismatch()
        val isOverlayIdNotMatch = reason.contains("The target app on the device is in a state unknown to Studio")

        val reinstallWhenTimeout = deployOptions.timeOutRetryTimes == 2 // try to reinstall apk at the third time
        val stopRetryWhenTimeout = deployOptions.timeOutRetryTimes >= 3
        if (isDeployTimeout && stopRetryWhenTimeout) {
            logger.warn("Deploy timeout, retry times: ${deployOptions.timeOutRetryTimes}, stop retry.")
            return null
        }

        if (isOverlayIdNotCorrect || isClassNotFoundException || isOverlayIdNotMatch || isDeployTimeout) {
            var isNeedTryDeyDeployFirst = false
            var isRetryDirectly = false
            @Suppress("KotlinConstantConditions")
            when {
                isDeployTimeout -> {
                    val isNeedReduce = deployData.overlays.size >= JuggSettings.overlayDeploySplitSizeFirstSlice
                    if (isNeedReduce) {
                        logger.warn("Got deploy timeout exception, reduce overlay and retry")
                        SliceDeployHelper(logger).onTimeout(IdeaDeviceAdb(deployOptions.device, logger))
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
                isClassNotFoundException -> {
                    logger.info("Got class not found exception, which means the deploy history mismatch with the device. Try recover deploy state.")
                    isNeedTryDeyDeployFirst = true
                }
                isOverlayIdNotMatch -> {
                    logger.info("The device's deploy status mismatch with this project, try recover deploy state.")
                    isNeedTryDeyDeployFirst = true
                }
            }
            if (!isRetryDirectly) {
                val (isSuccess, _) = recoverDeployState(
                    deployOptions.device,
                    deployOptions.indicator,
                    isNeedTryDeyDeployFirst,
                    deployOptions.isSkipExceptOverlayCheck,
                    compileUiHandler = deployOptions.compileUiHandler,
                )
                if (!isSuccess) {
                    logger.info("Try recover deploy state failed on retry.")
                    return DeployTaskResult(isSuccess = false, costTime = System.currentTimeMillis() - deployOptions.startTime,
                        failedReason = "Try recover deploy state failed on retry.")
                } else {
                    logger.info("Try recover deploy state success on retry.")
                }
            }
            val nextDeployOptions = deployOptions.copy(
                retryReason = reason, isSkipExceptOverlayCheck = true,
                timeOutRetryTimes = deployOptions.timeOutRetryTimes + if (isDeployTimeout) 1 else 0,
            )
            return deploy(nextDeployOptions)
        }

        tryRetryInstall(deployOptions, deployData, reason)?.let {
            return it
        }

        return null
    }

    private fun tryRetryInstall(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? {
        val isNeedUninstall = reason.contains("INSTALL_FAILED_INVALID_APK")
        if (isNeedUninstall) {
            val applicationIds = deployData.apks.map { it.applicationId }.toSet()
            logger.info("Got INSTALL_FAILED_INVALID_APK error, try uninstall apks. applicationIds: $applicationIds")
            val adbClient = AdbClient(deployOptions.device, LogWrapper(logger).also {
                it.alwaysLogAsDebug(true)
                it.allowVerbose(true)
            })
            applicationIds.forEach {
                logger.debug("Uninstalling $it...")
                adbClient.uninstall(it)
                logger.debug("Uninstalling $it finished.")
            }
            return deploy(deployOptions)
        }
        return null
    }

    private fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean {
        val adb = IdeaDeviceAdb(device, logger)
        val jvmtiAgentManagerHelper = JuggJvmtiAgentManagerHelper(logger)
        if (jvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy(adb, deployData)) {
            jvmtiAgentManagerHelper.pushAgentToApps(adb, deployData)
            deployTargetManager.restartApp(device)
        }

        return jvmtiAgentManagerHelper.isHasJvmtiCompatIssue(adb, deployData)
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
    private fun recoverDeployState(
        device: IDevice, indicator: ProgressIndicator?,
        isNeedTryDeyDeployFirst: Boolean,
        isSkipExceptOverlayCheck: Boolean,
        isInstallUpdateApk: Boolean = false,
        compileUiHandler: CompileUiHandler,
    ): Pair<Boolean, Boolean> {
        if (!isInstallUpdateApk) {
            logger.info("App not ready to deploy, recover deploy state from history.")
        }

        val isCleanAndReinstall = deployHistoryManager.isCleanAndReinstall
        val reinstallTips = if (isCleanAndReinstall) {
            "User triggered, start clean and reinstalling app..."
        } else {
            "Deploy state not match, start reinstalling app..."
        }

        if (isCleanAndReinstall) {
            indicator?.text = "Cleaning app..."
            val packageName = deployTargetManager.getPackageName()
            IdeaDeviceAdb(device, logger).execAdbShellCmd("pm clear $packageName")
        }

        // dry deploy first, if success, no need to reinstall and recover
        if (isNeedTryDeyDeployFirst && !isCleanAndReinstall) {
            val isSuccess = tryDryDeploy(device, isSkipExceptOverlayCheck, compileUiHandler = compileUiHandler)
            if (isSuccess) {
                logger.info("Deploy state matched, no need reinstall app.")
                return true to false
            } else {
                logger.warn(reinstallTips)
                indicator?.text = "Reinstalling app..."
                JuggRunningTask.notifyByBalloon(project, reinstallTips)
            }
        } else if (isInstallUpdateApk) {
            logger.info("App updated, start reinstalling app...")
            indicator?.text = "Installing app..."
            JuggRunningTask.notifyByBalloon(project, "App updated, start reinstalling app...")
        } else {
            logger.warn(reinstallTips)
            indicator?.text = "Reinstalling app..."
            JuggRunningTask.notifyByBalloon(project, reinstallTips)
        }

        // recover deploy state for device
        val deployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        logger.debug("going to install apks: ${deployData.apks.flatMap { it.files }.map { it.apkFile }}")

        val costTime = measureTimeMillis {
            runTask(device, deployData, compileUiHandler = compileUiHandler)
        }
        logger.info("Reinstalling app finished, cost ${costTime}ms.")

        // device need to be deployable, otherwise deployer can not get the correct arch of App.
        val isDeviceDeployable = waitingForDeployable(device, maxWaitTimeSecond = 5)
        if (!isDeviceDeployable) {
            logger.warn("App not deployable after reinstalling.")
            return false to false
        }

        deployFileManager.resetAfterReinstall()

        return true to true
    }

    /**
     * @return isSuccess
     */
    private fun tryDryDeploy(
        device: IDevice,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
    ): Boolean {
        logger.info("Start app and waiting app deployable.")
        if (!deployTargetManager.restartApp(device)) {
            logger.debug("Try start app failed")
            return false
        }

        val isDeviceDeployable = waitingForDeployable(device)
        if (!isDeviceDeployable) {
            logger.info("Dry deploy failed for app not launched.")
            return false
        }

        logger.info("Device online, try dry deploy.")
        return try {
            val dryDeployData = JuggDeployData.forDryDeploy(deployTargetManager.getApks())
            runTask(device, dryDeployData, isSkipExceptOverlayCheck = isSkipExceptOverlayCheck, compileUiHandler = compileUiHandler)
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

    private fun isNeedPushResourceApk(device: IDevice, data: JuggDeployData): Boolean {
        logger.trace("[PERF] CompatDeployHelper.isEnableCompatDeploy start, thread=${Thread.currentThread().name}")
        val compatStart = System.currentTimeMillis()
        val isEnableCompatDeploy = CompatDeployHelper(logger).isEnableCompatDeploy(IdeaDeviceAdb(device, logger), data)
        logger.trace("[PERF] CompatDeployHelper.isEnableCompatDeploy end, cost=${System.currentTimeMillis() - compatStart}ms, thread=${Thread.currentThread().name}")
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

    @SuppressLint("PrivateApi")
    private fun getOptionalIjPath(@Suppress("SameParameterValue") path: String): File? {
        // IJ does not bundle some large resources from android plugin, and downloads them on demand.
        try {
            val instance = AndroidProfilerDownloader.getInstance()
            instance.makeSureComponentIsInPlace()
            return instance.getHostDir(path)
        } catch (e: Throwable) { // NoClassDefFoundError | ClassNotFoundException
            // compat with Build #IU-243.22562.218
            val clazz = Class.forName("com.android.tools.idea.downloads.AndroidProfilerDownloader")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod("makeSureComponentIsInPlace").invoke(instance)
            return clazz.getMethod("getHostDir", String::class.java).invoke(instance, path) as File?
        }
    }
}

data class DeployTaskResult(
    val isSuccess: Boolean,
    val costTime: Long,
    val isCanFallback: Boolean = false,
    val deployType: JuggDeployData.DeployType? = null,
    val failedReason: String? = null,
    val costTimeExceptCheck: Long = costTime,
)

