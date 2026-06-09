package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.*
import com.android.tools.idea.execution.common.applychanges.BaseAction
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.utils.ILogger
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project

/**
 * Android Studio Giraffe
 */
open class GiraffeAsDeployerCompat : ChipmunkAsDeployerCompat() {

    override fun getSelectedDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)

        // find the first available devices
        val deviceFutures = deployTarget.getDevices(project)
        val devices = deviceFutures.ifReady
        if (!devices.isNullOrEmpty()) {
            return devices
        }

        return null
    }

    override fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        val adb = createLegacyAdbClient(device, logger)
        val apkInstaller = ApkInstaller(adb, session.toLegacyUiService(), session.rawInstaller as Installer, logger)
        return apkInstaller.install(packageName, apks, createInstallOptions(device, packageName), installMode.toLegacyInstallMode(), metrics.deployMetrics)
    }

    override fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
        val client = AndroidDebugClientReadyWaiter().waitForWaitingDebuggerClient(device, packageName)
        AndroidStudioDebuggerAttachStarter().attachExistingProcess(project, client)
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
