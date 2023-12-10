package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.*
import com.android.tools.idea.execution.common.applychanges.BaseAction
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.deployable.Deployable
import com.android.tools.idea.run.deployable.DeployableProvider
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.utils.ILogger
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project
import java.util.concurrent.ExecutionException

/**
 * Android Studio Giraffe
 */
open class GiraffeAsDeployerCompat : ChipmunkAsDeployerCompat() {

    override fun isSupportsSyncCallback(): Boolean {
        return true // actually Electric already supports sync callback. But I don't want to compatible with it now.
    }

    override fun getDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)

        // find the first available devices
        val deviceFutures = deployTarget.getDevices(project) ?: return null
        val devices = deviceFutures.ifReady
        if (!devices.isNullOrEmpty()) {
            return devices
        }

        return null
    }

    override fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: Deployer.InstallMode,
    ): Boolean {
        val apkInstaller = ApkInstaller(adb, service, installer, logger)
        return apkInstaller.install(packageName, apks, options, installMode, metrics.deployMetrics)
    }

    override fun toApkProvider(apkInfos: List<ApkInfo>): ApkProvider {
        return ApkProvider { apkInfos.toMutableList() }
    }

    /**
     * @see [BaseAction.getDisableMessage]
     */
    override fun getIdeDeployStateResult(project: Project): IdeDeployState {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return IdeDeployState.noAndroidConfiguration

        val deployableProvider = DeployableProvider.getInstance(project)
            ?: return IdeDeployState.noDeploymentProvider
        val deployable: Deployable?
        try {
            deployable = deployableProvider.getDeployable(selectedRunConfig)
            if (deployable == null) {
                return IdeDeployState.selectDeviceIsInvalid
            }
            if (!deployable.isOnline) {
                if (deployable.isUnauthorized) {
                    return IdeDeployState.deviceNotAuthorized
                } else {
                    return IdeDeployState.deviceNotConnected
                }
            }
            val versionFuture = deployable.versionAsync
            if (!versionFuture.isDone) {
                // Don't stall the EDT - if the Future isn't ready, just return false.
                return IdeDeployState.unknownDeviceApiLevel
            }
            if (versionFuture.get().apiLevel < IAsDeployerCompat.MIN_DEVICE_API) {
                return IdeDeployState.incompatibleDeviceApiLevel
            }
            if (deployable.searchClientsForPackage().isEmpty()) {
                return IdeDeployState.appNotRunningOrNotDebuggable
            }
        } catch (ex: InterruptedException) {
            return IdeDeployState.updateInterrupted
        } catch (ex: ExecutionException) {
            return IdeDeployState.unknownDeviceApiLevel
        } catch (ex: Exception) {
            return IdeDeployState.unexpectedException
        }
        return IdeDeployState.ok
    }

    private fun isApplyChangesRelevant(runConfiguration: RunConfiguration): Boolean {
        if (runConfiguration is RunConfigurationBase<*>) {
            return runConfiguration.putUserDataIfAbsent(
                BaseAction.SHOW_APPLY_CHANGES_UI,
                false
            ) // This is needed to prevent a NPE if the boolean isn't set.
        }
        return false
    }

}
