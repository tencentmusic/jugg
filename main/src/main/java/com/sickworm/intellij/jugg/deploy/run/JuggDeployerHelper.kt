package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.IncrementalDeployHelper
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployRunTaskExecutor
import com.sickworm.intellij.jugg.deploy.run.flow.JuggDeployHelperRunHostBridge
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer

/**
 * Create a deploy task.
 * [JuggDeployerHelper] -> [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployTask] -> [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer]
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper(
    private val deployTargetManager: IDeployTargetManager,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployStateManager: IDeployStateManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager,
    private val compileContextManager: CompileContextManager,
    private val juggServer: JuggServer,
    private val taskRunnerManager: TaskRunnerManager,
    private val logger: Logger,
    private val libraryTestApkBackfillHelper: ILibraryTestApkBackfillHelper = ILibraryTestApkBackfillHelper.NONE,
    private val deploymentService: IJuggDeployerDeploymentService,
    private val environment: IDeployHost,
    stateRecover: DeployStateRecover? = null,
    retryHandler: DeployRetryHandler? = null,
    deployRunTaskExecutor: IJuggDeployRunTaskExecutor? = null,
) : IJuggDeployHelperRunHost {

    private val deployRunHostBridge = JuggDeployHelperRunHostBridge()

    private val deployStateRecover: DeployStateRecover = stateRecover ?: DeployStateRecover(
        deployTargetManager = deployTargetManager,
        deployFileManager = deployFileManager,
        deployHistoryManager = deployHistoryManager,
        deployStateManager = deployStateManager,
        deployRunHost = deployRunHostBridge,
        deploymentService = deploymentService,
        environment = environment,
        logger = logger,
    )

    private val deployRetryHandler: DeployRetryHandler = retryHandler ?: DeployRetryHandler(
        deployTargetManager = deployTargetManager,
        deployFileManager = deployFileManager,
        deployStateRecover = deployStateRecover,
        juggServer = juggServer,
        deployRunHost = deployRunHostBridge,
        environment = environment,
        logger = logger,
    )

    private val deployOrchestrator = JuggDeployOrchestrator(
        deployTargetManager, deployHistoryManager, dependencyChangeManager, compileContextManager, juggServer,
        taskRunnerManager, deploymentService, environment, logger,
    )
    private val deployRunTaskExecutor: IJuggDeployRunTaskExecutor = deployRunTaskExecutor
        ?: object : IJuggDeployRunTaskExecutor {
            override fun execute(request: JuggDeployRunTaskRequest): LaunchResult = deployOrchestrator.execute(request)
        }

    init {
        deployRunHostBridge.bind(this)
    }

    override fun redeploy(deployOptions: DeployOptions): DeployTaskResult = deploy(deployOptions)

    override fun runRecoverDeployTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
        deferPostDeployLaunch: Boolean,
        isAllowDirectOverlayDeploy: Boolean,
    ) {
        val launchResult = runTask(
            JuggDeployRunTaskRequest(
                device = device,
                data = data,
                compileUiHandler = compileUiHandler,
                isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
                deferPostDeployLaunch = deferPostDeployLaunch,
                isAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy,
            ),
        )
        updateOverlayIdsAfterRecoverInstall(data, launchResult)
    }

    private fun updateOverlayIdsAfterRecoverInstall(data: JuggDeployData, launchResult: LaunchResult) {
        if (data.isInstall && launchResult.success) {
            deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
        }
    }

    private fun runTask(request: JuggDeployRunTaskRequest): LaunchResult = deployRunTaskExecutor.execute(request)

    fun deploy(deployOptions: DeployOptions): DeployTaskResult {
        logger.debug("deploy start, deployOptions: $deployOptions")
        fun costTime(): Long { return System.currentTimeMillis() - deployOptions.startTime }

        if (deployOptions.processHandler?.isCanceled == true) {
            logger.warn("Deploy canceled.")
            return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "deploy canceled")
        }

        logger.debug("Deploying... isInstall: ${deployOptions.isInstall}, isWarmUp: ${deployOptions.isWarmUp}")

        val deployState = deployStateManager.updateDeployState()
        logger.debug("Jugg deploy state: $deployState")

        var finalIsFallbackAllHotFix = false
        var deployData: JuggDeployData = JuggDeployData.forInstall(deployTargetManager.getApks())
        var incrementalSnapshot: IncrementalDeploySnapshot? = null
        return try {
            if (deployOptions.isInstall) {
                val outcome = deployInstall(deployOptions)
                deployData = outcome.deployData
                outcome.result
            } else if (JuggSettings.isEmbeddedToApk) {
                return embeddedToApk(deployOptions)
            } else if (!compileContextManager.compileContext.isDebuggable) {
                logger.warn("APK is not debuggable, will auto switch to embedded to apk mode.")
                return embeddedToApk(deployOptions)
            } else {
                incrementalSnapshot = IncrementalDeploySnapshot(deployData)
                val outcome = deployIncrementalChanges(deployOptions, deployData, incrementalSnapshot)
                deployData = outcome.deployData
                finalIsFallbackAllHotFix = outcome.finalIsFallbackAllHotFix
                outcome.result
            }
        } catch (e: OutOfMemoryError) {
            incrementalSnapshot?.let { snapshot ->
                deployData = snapshot.deployData
            }
            handleOutOfMemoryFailure(deployOptions, deployData, costTime(), e)
        } catch (e: Exception) {
            incrementalSnapshot?.let { snapshot ->
                deployData = snapshot.deployData
                finalIsFallbackAllHotFix = snapshot.finalIsFallbackAllHotFix
            }
            if (isOutOfMemoryFailure(e)) {
                return handleOutOfMemoryFailure(deployOptions, deployData, costTime(), e)
            }
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
                    val retryResult = deployRetryHandler.tryRetry(deployOptions, finalIsFallbackAllHotFix, deployData, reason)
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

            val isCanFallback = deployRetryHandler.isCanFallbackOnException(reason, deployOptions.isInstall)
            DeployTaskResult(isSuccess = false, deployType = deployData.deployType, isCanFallback = isCanFallback, costTime = costTime(), failedReason = reason)
        }
    }

    private fun deployInstall(deployOptions: DeployOptions): InstallDeployOutcome {
        fun costTime(): Long { return System.currentTimeMillis() - deployOptions.startTime }
        val apks = deployTargetManager.getApks()
        val apkFiles = apks.flatMap { it.files.map { it.apkFile } }
        if (apkFiles.size <= 1) {
            logger.info("Installing APK... ${apkFiles.first()}")
        } else {
            logger.info("Installing APK...\n${apkFiles.joinToString("\n")}")
        }
        var deployData = JuggDeployData.forInstall(apks)
        deployData = libraryTestApkBackfillHelper.backfillIfNeeded(
            spec = deployOptions.androidTestRunSpec,
            data = deployData,
            uiHandler = deployOptions.compileUiHandler,
        )
        val launchResult = runTask(JuggDeployRunTaskRequest.fromDeployOptions(deployOptions, deployData))
        if (!launchResult.success) {
            return InstallDeployOutcome(
                DeployTaskResult(
                    isSuccess = false,
                    costTime = costTime(),
                    deployType = deployData.deployType,
                    failedReason = launchResult.consoleError,
                ),
                deployData,
            )
        }
        if (deployOptions.isLastDevice) {
            logger.debug("Installing finished, update info after install.")
            deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
        }
        return InstallDeployOutcome(
            DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = deployData.deployType),
            deployData,
        )
    }

    private fun deployIncrementalChanges(
        deployOptions: DeployOptions,
        initialDeployData: JuggDeployData,
        incrementalSnapshot: IncrementalDeploySnapshot,
    ): ChangesDeployOutcome {
        fun costTime(): Long { return System.currentTimeMillis() - deployOptions.startTime }
        fun publishDeployState(deployData: JuggDeployData, finalIsFallbackAllHotFix: Boolean = incrementalSnapshot.finalIsFallbackAllHotFix) {
            incrementalSnapshot.deployData = deployData
            incrementalSnapshot.finalIsFallbackAllHotFix = finalIsFallbackAllHotFix
        }
        val device = deployOptions.device
        if (!deployTargetManager.hasDevice) {
            logger.warn("\nNo device connected, please check device is connected.")
            return ChangesDeployOutcome(
                DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "no device connected"),
                initialDeployData,
            )
        }

        if (deployOptions.isWarmUp && !deployStateManager.getDeployState(device).isReadyDeploy) {
            logger.info("Device not ready to warm up.")
            return ChangesDeployOutcome(
                DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "device not ready to warm up"),
                initialDeployData,
            )
        }

        var deployData = deployOptions.retryDeployData
            ?: deployFileManager.getDeployData(deployOptions.isWarmUp, isNeedPushResourceApk(device, initialDeployData))
        publishDeployState(deployData)
        deployData = libraryTestApkBackfillHelper.backfillIfNeeded(
            spec = deployOptions.androidTestRunSpec,
            data = deployData,
            uiHandler = deployOptions.compileUiHandler,
            installBackfilledApks = { backfilledApks ->
                val installData = JuggDeployData.forInstall(backfilledApks)
                val launchResult = runTask(
                    JuggDeployRunTaskRequest.fromDeployOptions(
                        deployOptions = deployOptions,
                        data = installData,
                        isSkipExceptOverlayCheck = true,
                    ),
                )
                if (!launchResult.success) {
                    throw JuggException.applyChangesFailed(launchResult)
                }
                deployHistoryManager.lastDeployOverlayIds = mergeOverlayIds(
                    deployHistoryManager.lastDeployOverlayIds,
                    launchResult.overlayIds,
                )
            },
        )
        publishDeployState(deployData)

        var isNeedReinstallApk = false
        val isRetry = deployOptions.retryReason != null // retry means we have already resigned the apk
        if (deployData.isNeedUpdateApk && !isRetry) {
            logger.info("Need resign APK to update files: ${deployData.updateApkFiles}.")
            logger.info("Resigning APK...")
            TimeLogger.start("insertFileAndResignApk")
            val (isSuccess, failedReason) = IncrementalDeployHelper(compileContextManager.compileContext, logger)
                .updateApk(deployData.apks, deployData.updateApkFiles)
            if (!isSuccess) {
                return ChangesDeployOutcome(
                    DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = failedReason),
                    deployData,
                )
            }
            logger.info("Resign APK file finished, cost ${TimeLogger.getCostTime("insertFileAndResignApk")}ms.\n")
            isNeedReinstallApk = true
        }

        var isRecoverWithReinstall = false
        val isProjectSwitchedThisRun = juggRunningTaskStatusManager.isProjectSwitchedThisRun
        if (isProjectSwitchedThisRun) {
            logger.debug("Project switched since last run, force recover deploy state.")
        }
        if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy || isProjectSwitchedThisRun) {
            if (deployStateManager.getDeployState(device).isReadyIncCompile) {
                val (isSuccess, isReinstalled) = deployStateRecover.recoverDeployState(
                    device,
                    deployOptions.progress,
                    isNeedDryDeployFirst = !isNeedReinstallApk,
                    isInstallUpdateApk = isNeedReinstallApk,
                    isSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck,
                    compileUiHandler = deployOptions.compileUiHandler,
                    allowDirectOverlayRecover = deployOptions.isAllowDirectOverlayDeploy,
                )
                if (!isSuccess) {
                    logger.info("Try recover deploy state failed.")
                    return ChangesDeployOutcome(
                        DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = "Try recover deploy state failed."),
                        deployData,
                    )
                } else {
                    logger.info("Try recover deploy state success.")
                    isRecoverWithReinstall = isReinstalled
                }
            } else {
                logger.warn("Invalid state for deploy.")
                return ChangesDeployOutcome(
                    DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = "Invalid state for deploy."),
                    deployData,
                )
            }
        }

        // get deploy data again after resigning apk (trigger full res deploy)
        if (isRecoverWithReinstall) {
            deployData = deployFileManager.getDeployData(
                deployOptions.isWarmUp,
                isNeedPushResourceApk(device, deployData),
            ).copy(isRecoverReplayAfterReinstall = true)
            publishDeployState(deployData)
        }

        val isClassNeedHotFix = deployData.hotFixModifiedClasses.isNotEmpty() ||
            dependencyChangeManager.getRemovedLibraryFiles().any { it.type == CompileFile.Type.Class }
        val finalIsFallbackAllHotFix = JuggSettings.isQuickFallbackToHotFix && isClassNeedHotFix
        if (finalIsFallbackAllHotFix) {
            deployData = deployData.toFallbackToHotFixData()
        }
        publishDeployState(deployData, finalIsFallbackAllHotFix)

        logDeployPayloadMemory(deployData)
        logger.debug("Deploying data(debug):\n$deployData")
        logger.info("Deploying data:\n${deployData.toDescString()}")
        if (deployData.isFullRes && !deployData.isCompatDeploy) {
            logger.info("It's first time to push overlays(full push), it may takes more times to resolved.")
        }
        val finalIsSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck || isRecoverWithReinstall
        val launchResult = runTask(
            JuggDeployRunTaskRequest.fromDeployOptions(
                deployOptions = deployOptions,
                data = deployData,
                isSkipExceptOverlayCheck = finalIsSkipExceptOverlayCheck,
                isDeviceReadyDeploy = deployStateManager.getDeployState(device).isReadyDeploy,
            ),
        )
        if (!launchResult.success) {
            if (DeployRetryHandler.isOutOfMemoryReason(launchResult.consoleError.orEmpty())) {
                return ChangesDeployOutcome(
                    handleOutOfMemoryFailure(deployOptions, deployData, costTime(), null),
                    deployData,
                    finalIsFallbackAllHotFix,
                )
            }
            if (deployOptions.androidTestRunSpec != null && deployOptions.isLastDevice) {
                logger.debug("AndroidTest launch failed after deploy, update deploy info before returning failure.")
                updateInfoAfterIncDeploy(launchResult, deployData)
            }
            return ChangesDeployOutcome(
                DeployTaskResult(
                    isSuccess = false,
                    isCanFallback = false,
                    costTime = costTime(),
                    deployType = deployData.deployType,
                    failedReason = launchResult.consoleError,
                ),
                deployData,
                finalIsFallbackAllHotFix,
            )
        }

        if (deployOptions.isLastDevice) {
            logger.debug("Deploying finished, update info after deploy.")
            updateInfoAfterIncDeploy(launchResult, deployData)
        }

        return ChangesDeployOutcome(
            DeployTaskResult(
                isSuccess = true,
                costTime = costTime(),
                deployType = deployData.deployType,
                costTimeExceptCheck = costTime() - launchResult.checkJvmtiCostTime,
                hasDeployChanges = !deployData.isEmpty,
            ),
            deployData,
            finalIsFallbackAllHotFix,
        )
    }

    private fun handleOutOfMemoryFailure(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        costTime: Long,
        error: Throwable?,
    ): DeployTaskResult {
        try {
            deployFileManager.clearResourceApkCache()
        } catch (cacheError: Throwable) {
            logger.debug("Clear resource APK cache after out of memory failure failed", cacheError)
        }
        if (deployOptions.isInstall) {
            logger.warn("Install APK failed. Reason: $OUT_OF_MEMORY_GUIDANCE")
        } else {
            logger.warn("Deploy Changes failed. Reason: $OUT_OF_MEMORY_GUIDANCE")
        }
        if (error != null) {
            logger.debug(error)
        }
        return DeployTaskResult(
            isSuccess = false,
            costTime = costTime,
            deployType = deployData.deployType,
            failedReason = OUT_OF_MEMORY_GUIDANCE,
        )
    }

    private fun isOutOfMemoryFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(16) {
            if (current is OutOfMemoryError || DeployRetryHandler.isOutOfMemoryReason(current?.message.orEmpty())) {
                return true
            }
            val next = current?.cause
            if (next === current) {
                return false
            }
            current = next
        }
        return false
    }

    private fun logDeployPayloadMemory(deployData: JuggDeployData) {
        val overlayBytes = deployData.overlays.sumOf { it.content.size.toLong() }
        val maxOverlayBytes = deployData.overlays.maxOfOrNull { it.content.size } ?: 0
        val runtime = Runtime.getRuntime()
        val heapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        logger.debug("Deploy payload memory: overlayCount=${deployData.overlays.size}, " +
                "overlayBytes=$overlayBytes, maxOverlayBytes=$maxOverlayBytes, " +
                "heapUsedBytes=$heapUsedBytes, heapMaxBytes=${runtime.maxMemory()}")
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
            logger.warn("Embedding APK failed. Reason: $failedReason")
            return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = deployOptions.costTime(), failedReason = failedReason)
        }

        val deployData = JuggDeployData.forInstall(apks)
        val launchResult = runTask(JuggDeployRunTaskRequest.fromDeployOptions(deployOptions, deployData))
        if (deployOptions.isLastDevice) {
            logger.debug("Embedded finished, update info after install.")
            deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
            updateInfoAfterIncDeploy(launchResult, incDeployData)
        }
        return DeployTaskResult(isSuccess = true, costTime = deployOptions.costTime(), deployType = deployData.deployType)
    }

    override fun tryRetryInstall(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? {
        val isNeedUninstall = reason.contains("INSTALL_FAILED_INVALID_APK")
        if (isNeedUninstall) {
            val applicationIds = deployData.apks.map { it.applicationId }.toSet()
            logger.info("Got INSTALL_FAILED_INVALID_APK error, try uninstall apks. applicationIds: $applicationIds")
            applicationIds.forEach {
                logger.debug("Uninstalling $it...")
                environment.uninstall(deployOptions.device, it, logger)
                logger.debug("Uninstalling $it finished.")
            }
            return deploy(deployOptions)
        }
        return null
    }

    override fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean {
        val adb = environment.createDeviceAdb(device, logger)
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

    private fun isNeedPushResourceApk(device: IDevice, data: JuggDeployData): Boolean {
        logger.trace("[PERF] CompatDeployHelper.isEnableCompatDeploy start, thread=${Thread.currentThread().name}")
        val compatStart = System.currentTimeMillis()
        val isEnableCompatDeploy = CompatDeployHelper(logger).isEnableCompatDeploy(environment.createDeviceAdb(device, logger), data)
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

    private data class InstallDeployOutcome(
        val result: DeployTaskResult,
        val deployData: JuggDeployData,
    )

    /**
     * Holds the latest incremental deploy payload while [deployIncrementalChanges] runs,
     * so [deploy] catch/retry paths see the same deployData as f2 inline flow.
     */
    private data class IncrementalDeploySnapshot(
        var deployData: JuggDeployData,
        var finalIsFallbackAllHotFix: Boolean = false,
    )

    private data class ChangesDeployOutcome(
        val result: DeployTaskResult,
        val deployData: JuggDeployData,
        val finalIsFallbackAllHotFix: Boolean = false,
    )

    companion object {
        const val DO_NOT_RETRY = "DO_NOT_RETRY"
        private const val OUT_OF_MEMORY_GUIDANCE = "Java heap space. Resource APK cache was cleared. " +
            "Restart Android Studio, increase the IDE heap, or run a Gradle install before retrying Jugg."
        /**
         * Merges newly installed APK overlay ids into the existing deploy-state checkpoint.
         */
        internal fun mergeOverlayIds(
            currentIds: Map<String, String>,
            newIds: Map<String, String>,
        ): Map<String, String> {
            return currentIds + newIds
        }
    }
}
