package com.sickworm.intellij.jugg.deploy.run

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.JuggInternalException
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.jarDexFileName
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManagerHelper
import com.sickworm.intellij.jugg.deploy.SliceDeployHelper
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapTransport
import com.sickworm.intellij.jugg.deploy.run.applychanges.AndroidDeployType
import com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployTask
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking

/** Runs the shared deploy lifecycle independently from IDEA or standalone host services. */
class JuggDeployOrchestrator(
    private val deployTargetManager: IDeployTargetManager, private val deployHistoryManager: IDeployHistoryManager,
    private val dependencyChangeManager: IDependencyChangeManager, private val compileContextManager: CompileContextManager,
    private val juggServer: JuggServer, private val taskRunnerManager: TaskRunnerManager,
    private val deploymentService: IJuggDeployerDeploymentService, private val environment: IDeployHost,
    private val logger: Logger,
) {
    private var isRunning = false

    /** Executes one device deployment while serializing the shared deployer state. */
    fun execute(request: JuggDeployRunTaskRequest): LaunchResult = synchronized(runTaskLock) {
        val device = request.device
        val data = request.data
        val compileUiHandler = request.compileUiHandler
        logger.debug("runTask start, isRunning: $isRunning")
        isRunning = true

        if (data.apks.isEmpty()) throw JuggInternalException.apkNotFound(data)
        val androidDeployType = when {
            data.isInstall -> AndroidDeployType.INSTALL
            data.isNeedRestartActivity -> AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY
            else -> AndroidDeployType.APPLY_CHANGES
        }
        if (androidDeployType == AndroidDeployType.INSTALL || request.forceDirectOverlayDeploy) {
            deployTargetManager.stopApp(device)
        }

        val detectJob = taskRunnerManager.runAsyncSafe("isNeedPushAgentAfterDeploy") {
            val adb = environment.createDeviceAdb(device, logger)
            JuggJvmtiAgentManagerHelper(logger).isNeedPushAgentAfterDeploy(adb, data)
        }
        if (!data.isInstall && dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            removeLibraryDexFiles(data, device)
        }

        val baseLaunchContext = LaunchContextFactory(environment, logger).create(
            device, deployHistoryManager.lastDeployOverlayIds, request.isSkipExceptOverlayCheck, compileUiHandler,
            request.isDeviceReadyDeploy, request.isAllowDirectOverlayDeploy, request.forceDirectOverlayDeploy,
        )
        val isDirectOverlayCandidate = DirectOverlaySwapTransport(baseLaunchContext, logger).canTry(data)
        val dataList = if (isDirectOverlayCandidate) {
            listOf(data)
        } else {
            val (firstSliceSize, sliceSize) = SliceDeployHelper(logger).get(baseLaunchContext.deviceAdb)
            data.splitData(firstSliceSize, sliceSize)
        }
        val launchResult = deploySlices(request, androidDeployType, baseLaunchContext, dataList)
        val isNeedPushAgentAfterDeploy = pushAgentAfterDeploy(device, data, detectJob, launchResult)
        val isNeedRestartApp = resolveRestartApp(device, data, compileUiHandler, isNeedPushAgentAfterDeploy)
        launchAfterDeploy(request, androidDeployType, launchResult, isNeedRestartApp)?.let { return it }
        checkJvmti(device, data, launchResult, isNeedPushAgentAfterDeploy, isNeedRestartApp)
        logger.debug("runTask end")
        isRunning = false
        launchResult
    }

    private fun deploySlices(
        request: JuggDeployRunTaskRequest, androidDeployType: AndroidDeployType,
        baseLaunchContext: LaunchContext, dataList: List<JuggDeployData>,
    ): LaunchResult {
        logger.debug("deploy_to_device size: ${dataList.size}")
        TimeLogger.start("deploy_to_device")
        lateinit var launchResult: LaunchResult
        var successfulSliceCount = 0
        dataList.forEachIndexed { index, splitData ->
            if (dataList.size > 1) TimeLogger.start("deploy_to_device_slice$index")
            val classCount = splitData.newClasses.size + splitData.hotFixModifiedClasses.size +
                splitData.hotReloadModifiedClasses.size
            logger.debug("deploy_to_device_slice$index, classes: $classCount, overlays: ${splitData.overlays.size}")
            try {
                val launchContext = baseLaunchContext.withSkipExceptOverlayCheck(request.isSkipExceptOverlayCheck || index != 0)
                val task = JuggDeployTask(
                    androidDeployType.forDeploySlice(index, dataList.lastIndex), splitData, deploymentService, logger,
                )
                launchResult = task.run(launchContext)
                if (!launchResult.success) throw JuggException.applyChangesFailed(launchResult)
                successfulSliceCount++
            } catch (e: Exception) {
                if (dataList.size > 1 && successfulSliceCount > 0) {
                    clearPartialOverlayAfterSliceFailure(request.device, request.data)
                }
                throw e
            }
            if (dataList.size > 1) TimeLogger.end("deploy_to_device_slice$index", logger)
        }
        TimeLogger.end("deploy_to_device", logger)
        return launchResult
    }

    private fun pushAgentAfterDeploy(
        device: IDevice, data: JuggDeployData,
        detectJob: Deferred<Boolean?>, launchResult: LaunchResult,
    ): Boolean {
        TimeLogger.start("push_agent")
        val isNeedPushAgentAfterDeploy = runBlocking { detectJob.await() ?: false }
        logger.debug("isNeedPushAgentAfterDeploy: $isNeedPushAgentAfterDeploy")
        if (isNeedPushAgentAfterDeploy) {
            JuggJvmtiAgentManagerHelper(logger).pushAgentToApps(environment.createDeviceAdb(device, logger), data)
        }
        launchResult.pushingAgentCostTime = TimeLogger.end("push_agent", logger)
        return isNeedPushAgentAfterDeploy
    }

    private fun resolveRestartApp(
        device: IDevice, data: JuggDeployData,
        compileUiHandler: CompileUiHandler, isNeedPushAgentAfterDeploy: Boolean,
    ): Boolean {
        var isNeedRestartApp = data.isNeedRestartApp
        if (compileUiHandler.isDebugRun && !isNeedRestartApp) {
            logger.info("Debug run requires app restart before attaching debugger.")
            isNeedRestartApp = true
        } else if (compileUiHandler.isAlwaysRestartApp && !isNeedRestartApp && !data.isEmpty) {
            logger.info("Always restart app is set, restart app.")
            isNeedRestartApp = true
        }
        if (com.sickworm.intellij.jugg.ide.bean.JuggSettings.isAlwaysRestartAppAfterDeployment) {
            logger.info("User require always restart app after deployment, restart app.")
            isNeedRestartApp = true
        }
        if ((isNeedPushAgentAfterDeploy || data.isFullRes) && !isNeedRestartApp &&
            environment.hasRelaunchActivityIssues(environment.createDeviceAdb(device, logger), logger)) {
            val deployTarget = if (data.isFullRes) "deploy res" else "deploy"
            logger.info("Fix JVMTI compatibility issue for Android >=15 below Android Studio Meerkat at first time " +
                    "$deployTarget, restart app.")
            isNeedRestartApp = true
        }
        return isNeedRestartApp
    }

    private fun launchAfterDeploy(
        request: JuggDeployRunTaskRequest, androidDeployType: AndroidDeployType,
        launchResult: LaunchResult, isNeedRestartApp: Boolean,
    ): LaunchResult? {
        if (request.androidTestRunSpec != null) return environment.launchAndroidTest(request, request.data, launchResult)
        if (request.deferPostDeployLaunch) {
            logger.debug("Defer post-deploy launch; follow-up deploy will restart the app.")
        } else if (isNeedRestartApp || androidDeployType == AndroidDeployType.INSTALL) {
            logger.debug("Restarting app...")
            if (request.compileUiHandler.isDebugRun) {
                deployTargetManager.restartAppForDebug(request.device)
            } else {
                deployTargetManager.restartApp(request.device)
            }
        } else if (!deployTargetManager.isAppForeground(request.device)) {
            logger.debug("Starting app...")
            deployTargetManager.startApp(request.device)
        } else {
            logger.debug("App foreground, no need to restart app.")
        }
        return null
    }

    private fun checkJvmti(
        device: IDevice, data: JuggDeployData, launchResult: LaunchResult,
        isNeedPushAgentAfterDeploy: Boolean, isNeedRestartApp: Boolean,
    ) {
        TimeLogger.start("check_jvmti")
        if (isNeedPushAgentAfterDeploy && isNeedRestartApp) {
            val adb = environment.createDeviceAdb(device, logger)
            if (JuggJvmtiAgentManagerHelper(logger).isHasJvmtiCompatIssue(adb, data) && !data.isCompatDeploy) {
                juggServer.report {
                    action = "jvmti_compat_issue"
                    detail = Gson().toJson(mapOf("device" to adb.displayName, "application" to data.apks.firstOrNull()?.applicationId))
                }
                throw IllegalStateException(DeployRetryHandler.REDEPLOY_WITH_COMPAT_MESSAGE)
            }
        }
        launchResult.checkJvmtiCostTime = TimeLogger.end("check_jvmti", logger)
    }

    private fun AndroidDeployType.forDeploySlice(sliceIndex: Int, lastSliceIndex: Int): AndroidDeployType {
        if (this == AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY && sliceIndex < lastSliceIndex) {
            return AndroidDeployType.APPLY_CHANGES
        }
        return this
    }

    private fun clearPartialOverlayAfterSliceFailure(device: IDevice, data: JuggDeployData) {
        val applicationIds = data.apks.map { it.applicationId }.distinct().filter { it.isNotBlank() }
        if (applicationIds.isEmpty()) return
        val adb = environment.createDeviceAdb(device, logger)
        applicationIds.forEach { applicationId ->
            logger.warn("Split deploy failed after partial success; clearing partial overlay for $applicationId.")
            runCatching { adb.execAdbShellCmd("run-as $applicationId rm -rf code_cache/.overlay") }
                .onFailure { logger.warn("Failed to clear partial overlay for $applicationId.", it) }
        }
    }

    private fun removeLibraryDexFiles(data: JuggDeployData, device: IDevice) {
        val removedDexFilesByVersionRollback = dependencyChangeManager.getRemovedLibraryFiles()
            .filter { it.type == CompileFile.Type.Class }.map(ChangedFile::jarDexFileName).toSet()
        logger.debug("removedDexFilesByVersionRollback: $removedDexFilesByVersionRollback")

        val deployLibraryDexFiles = (data.hotReloadModifiedClasses + data.hotFixModifiedClasses)
            .filter { it.isLibraryDex }.map { it.name + ".dex" }.toSet()
        val deployedDexFiles = compileContextManager.compileContext.deployedFiles
            .filter { it.file.name.endsWith(".dex") }.map { it.file.name }.toSet()
        val removedDexFilesByClassRollback = dependencyChangeManager.getNewLibraryFiles()
            .filter { it.type == CompileFile.Type.Class }.map { it.jarDexFileName }
            .filter { deployedDexFiles.contains(it) && !deployLibraryDexFiles.contains(it) }.toSet()
        logger.debug("deployLibraryDexFiles: $deployLibraryDexFiles")
        logger.debug("deployedDexFiles: $deployedDexFiles")
        logger.debug("removedDexFilesByClassRollback: $removedDexFilesByClassRollback")

        val removedDexFiles = removedDexFilesByVersionRollback + removedDexFilesByClassRollback
        if (removedDexFiles.isEmpty()) return
        TimeLogger.start("remove dex")
        logger.info("Before deploy, need to delete reverted libraries dex:\n" +
                removedDexFiles.joinToString("\n    ", prefix = "    "))
        removedDexFiles.forEach { dexFileName ->
            data.apks.filter { !it.isOtherTargetingTestApk }.forEach {
                val packageName = it.applicationId
                logger.debug("delete $packageName - $dexFileName")
                try {
                    AdbCmdHelper(environment.createDeviceAdb(device, logger), logger)
                        .deleteDeployedDexFile(packageName, dexFileName)
                } catch (e: Exception) {
                    logger.debug("delete $packageName - $dexFileName failed", e)
                    logger.warn("delete $packageName - $dexFileName failed, reason:\n$e")
                }
            }
        }
        logger.info("Delete removed libraries dex finished.")
        TimeLogger.end("remove dex", logger)
    }

    companion object {
        private val runTaskLock = Object()
    }
}
