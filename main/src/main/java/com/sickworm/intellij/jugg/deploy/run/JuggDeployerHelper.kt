package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.IdeInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.JuggStateListener
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.android.download.AndroidProfilerDownloader
import java.io.File

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
    private val deployStateListenerGetter: () -> JuggStateListener,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployerHelper"),
    private var installPathProvider: Computable<String> = Computable<String> {
        CopyEmbeddedDistributionPaths().get()
    },
) {

    private val deployStateListener get() = deployStateListenerGetter.invoke()

    @Synchronized
    private fun runTask(data: JuggDeployData, type: JuggDeployType) {
        if (data.apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(data)
        }

        val task = JuggDeployTask(project, installPathProvider, type, data)

        val consolePrinter = ConsolePrinter(logger)
        val device = deployTargetManager.getDevice()
        val launchContext = LaunchContext(consolePrinter, device)
        val launchResult = task.run(launchContext)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        if (data.isNeedRestartApp || type == JuggDeployType.INSTALL) {
            deployTargetManager.restartApp()
        }
    }

    fun deploy(isInstall: Boolean = false, isWarmUp: Boolean = false): DeployTaskResult {
        logger.debug("Deploying... isInstall: $isInstall, isWarmUp: $isWarmUp")

        val deployState = deployStateManager.updateDeployState()
        logger.info("Jugg deploy state: $deployState")

        val statTime = System.currentTimeMillis()
        fun costTime(): Long { return System.currentTimeMillis() - statTime }

        val type: JuggDeployType
        return try {
            if (isInstall) {
                val apks = deployTargetManager.getApks()
                logger.info("Installing APK... ${apks.firstOrNull()?.files?.first()?.apkFile}")
                val deployData = JuggDeployData.forInstall(apks)
                type = JuggDeployType.INSTALL
                runTask(deployData, JuggDeployType.INSTALL)
            } else {
                if (!deployTargetManager.hasDevice) {
                    logger.info("No device connected, stop deploy.")
                    return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "no device connected")
                }
                if (isWarmUp && !deployStateManager.deployState.isReadyDeploy) {
                    logger.info("device not ready to warm up.")
                    return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "device not ready to warm up")
                }

                if (!deployStateManager.deployState.isReadyDeploy) {
                    if (deployStateManager.deployState.isReadyIncCompile) {
                        if (!recoverDeployState()) {
                            logger.info("Try recover deploy state failed.")
                            return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "Try recover deploy state failed.")
                        } else {
                            logger.info("Try recover deploy state success.")
                        }
                    } else {
                        logger.warn("Invalid state for deploy.")
                        return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "Invalid state for deploy.")
                    }
                }
                val deployData = deployFileManager.getDeployData(isWarmUp)
                type = if (deployData.isNeedRestartApp) {
                    JuggDeployType.APPLY_CHANGES_AND_RESTART
                } else {
                    JuggDeployType.APPLY_CHANGES
                }
                logger.info("Deploying data:\n$deployData")
                if (deployData.isFullOverlays) {
                    logger.info("It's first time to push overlays(full push), it may takes more times to resolved.")
                }
                runTask(deployData, type)

                deployStateListener.onDeployed(
                    false,
                    deployFileManager.getCompiledFiles().map { it.file },
                )
                updateInfoAfterIncDeploy(deployData)
            }
            DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = type.toString())
        } catch (e: Exception) {
            if (isInstall) {
                logger.warn("Install APK failed. Reason: ${e.message ?: e.cause?.message}")
                logger.debug(e)
            } else {
                logger.warn("Deploy Changes failed. Reason: ${e.message ?: e.cause?.message}")
                logger.debug(e)
            }
            DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "Exception: $e")
        }
    }

    private fun updateInfoAfterIncDeploy(deployData: JuggDeployData) {
        val compiledFiles = deployFileManager.getCompiledFiles()
        val deployedFiles = deployFileManager.getStagingFiles()
        deployHistoryManager.updateHistoryOnAfterDeployed(compiledFiles, deployedFiles)
        deployFileManager.commit(deployData)
    }

    /**
     * Redeploy apk and compiled files.
     * Will check deploy state on device first. If matched, won't reinstall apk and redeploy compiled files.
     */
    private fun recoverDeployState(): Boolean {
        logger.info("Recover deploy state from history.")

        // dry deploy first, if success, no need to reinstall and recover
        if (tryDryDeploy()) {
            logger.info("Deploy state matched, no need reinstall app.")
            return true
        }
        logger.info("Need reinstall app.")

        // recover deploy state for device
        val deployData = deployFileManager.getDeployData()
        runTask(deployData, JuggDeployType.INSTALL)
        val isDeviceDeployable = waitingForDeployable()
        if (!isDeviceDeployable) {
            logger.warn("Recovery failed for app not launched.")
            return false
        }

        logger.info("Device online, start recover and deploy.")
        return true
    }

    private fun tryDryDeploy(): Boolean {
        logger.info("Start app directly.")
        if (!deployTargetManager.restartApp()) {
            logger.debug("Try start app failed")
            return false
        }
        val isDeviceDeployable = waitingForDeployable()
        if (!isDeviceDeployable) {
            logger.warn("Dry deploy failed for app not launched.")
            return false
        }

        logger.info("Device online, try dry deploy.")
        return try {
            val deployData = deployFileManager.getDeployData()
            val dryDeployData = JuggDeployData.forInstall(deployData.apks)
            runTask(dryDeployData, JuggDeployType.INSTALL)
            true
        } catch (e: Exception) {
            logger.debug("Dry deploy failed, reason: ${e.message}")
            false
        }
    }

    private fun waitingForDeployable(): Boolean {
        val maxWaitTimeSecond = 5
        var waitedTimeSecond = 0
        val waitGapMillSecond = 1
        while (waitedTimeSecond < maxWaitTimeSecond) {
            Thread.sleep(waitGapMillSecond * 1000L)
            waitedTimeSecond += waitGapMillSecond
            logger.info("($waitedTimeSecond/$maxWaitTimeSecond)waiting app launched...")
            deployStateManager.updateDeployState()
            if (deployStateManager.deployState.isReadyDeploy) {
                return true
            }
        }

        logger.warn("App not launched, please check the app is started and adb is not occupied by other process")
        return false
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
    val deployType: String? = null,
    val failedReason: String? = null,
)