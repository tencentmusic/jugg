package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.IncrementalDeployHelper
import com.sickworm.intellij.jugg.compiler.jarDexFileName
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestApkSelector
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModel
import com.sickworm.intellij.jugg.deploy.run.applychanges.AndroidDeployType
import com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployTask
import com.sickworm.intellij.jugg.deploy.run.applychanges.LaunchContext
import com.sickworm.intellij.jugg.deploy.run.utils.CopyEmbeddedDistributionPaths
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployRunTaskExecutor
import com.sickworm.intellij.jugg.deploy.run.instrument.LibraryTestApkBackfillHelper
import com.sickworm.intellij.jugg.deploy.run.instrument.TestLauncher
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Create a deploy task.
 * [JuggDeployerHelper] -> [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployTask] -> [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer]
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
    private val pathManager: JuggPathManager = JuggPathManager(File(project.basePath ?: ".")),
    private val libraryTestApkBackfillHelper: LibraryTestApkBackfillHelper = LibraryTestApkBackfillHelper(
        project = project,
        pathManager = pathManager,
        deployHistoryManager = deployHistoryManager,
        compileContextManager = compileContextManager,
        compileClientFactory = {
            LocalGradleCompileClient(
                pathManager.projectDir,
                pathManager.localClasspathStoragePathManager.classpathDir,
                LocalGradleCompileClient.buildCompileEnv(project, logger),
                logger,
            )
        },
        logger = logger,
        onApksBackfilled = { apks ->
            deployTargetManager.setApks(apks)
            deployFileManager.updateApks(apks)
        },
    ),
    private val deploymentService: IJuggDeployerDeploymentService = JuggDeploymentService,
    private val asDeployerCompat: IAsDeployerCompat = AsDeployerCompat,
    private val deviceAdbFactory: (IDevice, Logger) -> IDeviceAdb = { device, ideaLogger ->
        IdeaDeviceAdb(device, ideaLogger)
    },
    injectedDeployStateRecover: DeployStateRecover? = null,
    injectedDeployRetryHandler: DeployRetryHandler? = null,
    injectedDeployRunTaskExecutor: IJuggDeployRunTaskExecutor? = null,
    private var installPathProvider: Computable<String> = Computable<String> {
        CopyEmbeddedDistributionPaths().get()
    },
) : IJuggDeployHelperRunHost {

    private val deployStateRecover: DeployStateRecover = injectedDeployStateRecover ?: DeployStateRecover(
        project = project,
        deployTargetManager = deployTargetManager,
        deployFileManager = deployFileManager,
        deployHistoryManager = deployHistoryManager,
        deployStateManager = deployStateManager,
        deployRunHost = this,
        deploymentService = deploymentService,
        deviceAdbFactory = deviceAdbFactory,
        logger = logger,
    )

    private val deployRetryHandler: DeployRetryHandler = injectedDeployRetryHandler ?: DeployRetryHandler(
        deployTargetManager = deployTargetManager,
        deployFileManager = deployFileManager,
        deployStateRecover = deployStateRecover,
        juggServer = juggServer,
        deployRunHost = this,
        logger = logger,
    )

    private val deployRunTaskExecutor: IJuggDeployRunTaskExecutor = injectedDeployRunTaskExecutor ?: object : IJuggDeployRunTaskExecutor {
        override fun execute(request: JuggDeployRunTaskRequest): LaunchResult = executeDeployRunTask(request)
    }

    private var isRunning = false

    override fun redeploy(deployOptions: DeployOptions): DeployTaskResult = deploy(deployOptions)

    override fun runRecoverDeployTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
    ) {
        runTask(
            JuggDeployRunTaskRequest(
                device = device,
                data = data,
                compileUiHandler = compileUiHandler,
                isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
            ),
        )
    }

    private fun runTask(request: JuggDeployRunTaskRequest): LaunchResult = deployRunTaskExecutor.execute(request)

    private fun executeDeployRunTask(request: JuggDeployRunTaskRequest): LaunchResult = synchronized(runTaskLock) {
        val device = request.device
        val data = request.data
        val isSkipExceptOverlayCheck = request.isSkipExceptOverlayCheck
        val compileUiHandler = request.compileUiHandler
        val isMultipleDevices = request.isMultipleDevices
        val isLastDevice = request.isLastDevice
        val androidTestRunSpec = request.androidTestRunSpec
        val androidTestResultModel = request.androidTestResultModel
        val isDeviceReadyDeploy = request.isDeviceReadyDeploy
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
            val adb = deviceAdbFactory(device, logger)
            JuggJvmtiAgentManagerHelper(logger).isNeedPushAgentAfterDeploy(adb, data)
        }

        if (!data.isInstall && dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            removeLibraryDexFiles(data, device)
        }

        val (firstSliceSize, sliceSize) = SliceDeployHelper(logger).get(deviceAdbFactory(device, logger))
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
            val deviceAdb = deviceAdbFactory(device, logger)
            val launchContext = LaunchContext(
                device = device,
                deviceAdb = deviceAdb,
                exceptOverlayIds = deployHistoryManager.lastDeployOverlayIds,
                isSkipExceptOverlayCheck = isSliceSkipExceptOverlayCheck,
                compileUiHandler = compileUiHandler,
                isDeviceReadyDeploy = isDeviceReadyDeploy,
            )
            val task = JuggDeployTask(
                project = project,
                installPathProvider = installPathProvider,
                type = androidDeployType,
                data = splitData,
                deploymentService = deploymentService,
                asDeployerCompat = asDeployerCompat,
            )
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
                val adb = deviceAdbFactory(device, logger)
                JuggJvmtiAgentManagerHelper(logger).pushAgentToApps(adb, data)
            }
        }
        launchResult.pushingAgentCostTime = TimeLogger.end("push_agent", logger)

        var isNeedRestartApp = data.isNeedRestartApp

        if (compileUiHandler.isAlwaysRestartApp && !isNeedRestartApp && !data.isEmpty) {
            logger.info("Always restart app is set, restart app.")
            isNeedRestartApp = true
        }

        if (JuggSettings.isAlwaysRestartAppAfterDeployment) {
            logger.info("User require always restart app after deployment, restart app.")
            isNeedRestartApp = true
        }

        if ((isNeedPushAgentAfterDeploy && !isNeedRestartApp) || (data.isFullRes && !isNeedRestartApp)) {
            val adb = deviceAdbFactory(device, logger)
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

        if (androidTestRunSpec != null) {
            // AM_INSTRUMENT launch strategy: run instrumentation tests instead of starting the app.
            val projectInfo = compileContextManager.getProjectInfo()
            val testApk = AndroidTestApkSelector.select(
                spec = androidTestRunSpec,
                apks = data.apks,
                projectDir = projectInfo.modules.values.firstOrNull()?.projectRootDir
                    ?: File(androidTestRunSpec.sourcePath.orEmpty()).parentFile
                    ?: File("."),
                modules = projectInfo.modules.values,
            )
            if (testApk == null) {
                logger.warn("androidTestRunSpec provided but no test APK found in deploy data; skipping test launch.")
            } else {
                val launcher = TestLauncher(
                    devices = listOf(device),
                    spec = androidTestRunSpec,
                    testApk = testApk,
                    consoleOutput = { line -> compileUiHandler.onDeployUiMessage(line) },
                    showDeviceSuite = isMultipleDevices,
                    testEventSinkFactory = { deviceName, isShowDeviceSuite ->
                        compileUiHandler.testEventSinkFactory?.invoke(deviceName, isShowDeviceSuite)
                    },
                    cancelSignal = { compileUiHandler.isCanceled },
                    logger = logger,
                    resultModel = androidTestResultModel ?: AndroidTestResultModel(),
                    printAggregatedResult = isLastDevice && isMultipleDevices,
                )
                val success = launcher.run()
                if (!success) {
                    logger.warn("Instrumentation test run reported failures.")
                    return LaunchResult(false, 1, "Instrumentation test run reported failures.", emptyMap())
                }
            }
        } else if (isNeedRestartApp || androidDeployType == AndroidDeployType.INSTALL) {
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
            val adb = deviceAdbFactory(device, logger)
            val isHasJvmtiCompatIssue = JuggJvmtiAgentManagerHelper(logger).isHasJvmtiCompatIssue(adb, data)
            if (isHasJvmtiCompatIssue && !data.isCompatDeploy) {
                juggServer.report {
                    action = "jvmti_compat_issue"
                    detail = Gson().toJson(mapOf(
                        "device" to adb.displayName,
                        "application" to data.apks.firstOrNull()?.applicationId,
                    ))
                }
                throw IllegalStateException(DeployRetryHandler.REDEPLOY_WITH_COMPAT_MESSAGE)
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
                data.apks
                    .filter { !it.isOtherTargetingTestApk }
                    .forEach {
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
        } catch (e: Exception) {
            incrementalSnapshot?.let { snapshot ->
                deployData = snapshot.deployData
                finalIsFallbackAllHotFix = snapshot.finalIsFallbackAllHotFix
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
        val deployData = JuggDeployData.forInstall(apks)
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
        if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy) {
            if (deployStateManager.getDeployState(device).isReadyIncCompile) {
                val (isSuccess, isReinstalled) = deployStateRecover.recoverDeployState(
                    device,
                    deployOptions.indicator,
                    isNeedDryDeployFirst = !isNeedReinstallApk,
                    isInstallUpdateApk = isNeedReinstallApk,
                    isSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck,
                    compileUiHandler = deployOptions.compileUiHandler,
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
            deployData = deployFileManager.getDeployData(deployOptions.isWarmUp, isNeedPushResourceApk(device, deployData))
            publishDeployState(deployData)
        }

        val isClassNeedHotFix = deployData.hotFixModifiedClasses.isNotEmpty() ||
            dependencyChangeManager.getRemovedLibraryFiles().any { it.type == CompileFile.Type.Class }
        val finalIsFallbackAllHotFix = JuggSettings.isQuickFallbackToHotFix && isClassNeedHotFix
        if (finalIsFallbackAllHotFix) {
            deployData = deployData.toFallbackToHotFixData()
        }
        publishDeployState(deployData, finalIsFallbackAllHotFix)

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

    override fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean {
        val adb = deviceAdbFactory(device, logger)
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
        val isEnableCompatDeploy = CompatDeployHelper(logger).isEnableCompatDeploy(deviceAdbFactory(device, logger), data)
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
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val runTaskLock = Object()

        const val DO_NOT_RETRY = "DO_NOT_RETRY"
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
