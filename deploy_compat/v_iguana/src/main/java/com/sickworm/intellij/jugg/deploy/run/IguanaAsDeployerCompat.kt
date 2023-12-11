package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.*
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkParser
import com.android.tools.deployer.model.App
import com.android.tools.idea.execution.common.AndroidExecutionTarget
import com.android.tools.idea.execution.common.applychanges.BaseAction
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.run.ApkProvisionException
import com.android.tools.idea.run.DeploymentApplicationService
import com.android.utils.ILogger
import com.intellij.execution.*
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Android Studio Giraffe
 */
open class IguanaAsDeployerCompat: HedgehogAsDeployerCompat() {

    /**
     * @see [BaseAction.getDisableMessage]
     */
    override fun getIdeDeployStateResult(project: Project, device: IDevice): IdeDeployState {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return IdeDeployState.noAndroidConfiguration

        val applicationIdProvider = project.getProjectSystem().getApplicationIdProvider(selectedRunConfig)
            ?: return IdeDeployState.canNotDetectApplicationId
        val applicationId: String = try {
            applicationIdProvider.packageName
        } catch (var11: ApkProvisionException) {
            return IdeDeployState.canNotDetectApplicationId
        }

        if (device.state == IDevice.DeviceState.UNAUTHORIZED) {
            return IdeDeployState.deviceNotAuthorized
        }
        if (!device.version.isGreaterOrEqualThan(IAsDeployerCompat.MIN_DEVICE_API)) {
            return IdeDeployState.incompatibleDeviceApiLevel
        }
        if (DeploymentApplicationService.instance.findClient(device, applicationId).isEmpty()) {
            return IdeDeployState.appNotRunningOrNotDebuggable
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
        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        return apkInstaller.install(app, options, installMode, metrics.deployMetrics)
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return ApkParser.parsePaths(paths)
    }
}
