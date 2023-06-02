package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.IdeInfo
import com.android.tools.idea.run.ConsolePrinter
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
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

    fun runTask(data: JuggDeployData, isInstall: Boolean = false) {
        if (data.apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(data)
        }

        val type = when {
            isInstall -> JuggDeployType.INSTALL
            data.isNeedRestartApp -> JuggDeployType.HOT_FIX
            else -> JuggDeployType.HOT_RELOAD
        }
        val task = JuggDeployTask(project, installPathProvider, type, data)

        val consolePrinter = ConsolePrinter(logger)
        val device = deployTargetManager.getDevice()
        val launchContext = LaunchContext(consolePrinter, device)
        val launchResult = task.run(launchContext)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        if (data.isNeedRestartApp || isInstall) {
            deployTargetManager.restartApp()
        }
    }

    fun deploy(isInstall: Boolean = false): DeployTaskResult {
        return try {
            if (isInstall) {
                val apks = deployTargetManager.getApks()
                logger.info("Installing APK... ${apks.firstOrNull()?.file?.absolutePath}")
                val deployData = JuggDeployData.forInstall(apks)
                runTask(deployData, true)
            } else {
                if (!deployStateManager.deployState.isReadyDeploy) {
                    if (deployStateManager.deployState.isReadyIncCompile) {
                        if (!recoverDeployState()) {
                            logger.info("Try recover deploy state failed.")
                            return DeployTaskResult(isSuccess = false)
                        }
                    } else {
                        logger.warn("Invalid state for deploy.")
                        return DeployTaskResult(isSuccess = false)
                    }
                }
                val deployData = deployFileManager.getDeployData()
                logger.info("Deploying data:\n$deployData")
                runTask(deployData, false)

                deployStateListener.onDeployed(
                    false,
                    deployFileManager.getCompiledFiles().map { it.file },
                )
                updateInfoAfterIncDeploy(deployData)
            }
            DeployTaskResult(isSuccess = true)
        } catch (e: Exception) {
            if (isInstall) {
                logger.warn("Install APK failed. Reason: ${e.message}")
            } else {
                logger.warn("Deploy Changes failed. Reason: ${e.message}")
            }
            DeployTaskResult(isSuccess = false)
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
        runTask(deployData, true)
        val isDeviceDeployable = waitingForDeployable()
        if (!isDeviceDeployable) {
            logger.warn("Recovery failed for app not launched.")
            return false
        }

        logger.info("Device online, start recover and deploy.")
        return false
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
            val dryDeployData = JuggDeployData(deployData.apks, emptyList(), emptyList(), emptyList(), emptyList())
            runTask(dryDeployData)
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
)