package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.AndroidExecutionTarget
import com.android.tools.idea.execution.common.AppRunConfiguration
import com.android.tools.idea.execution.common.applychanges.BaseAction
import com.android.tools.idea.run.DeploymentApplicationService
import com.android.tools.idea.run.DeploymentService
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project

/**
 * Android Studio Giraffe
 */
open class HedgehogAsDeployerCompat: GiraffeAsDeployerCompat() {

    /**
     * @see [BaseAction.getDisableMessage]
     */
    override fun getIdeDeployStateResult(project: Project, device: IDevice): IdeDeployState {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return IdeDeployState.noAndroidConfiguration

        val packageName = (selectedRunConfig as AppRunConfiguration).appId ?: ""

        return if (device.state == IDevice.DeviceState.UNAUTHORIZED) {
            IdeDeployState.deviceNotAuthorized
        } else if (!device.version.isGreaterOrEqualThan(IAsDeployerCompat.MIN_DEVICE_API)) {
            IdeDeployState.incompatibleDeviceApiLevel
        } else if (DeploymentApplicationService.instance.findClient(device, packageName).isEmpty()) {
            IdeDeployState.appNotRunningOrNotDebuggable
        } else {
            IdeDeployState.ok
        }
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

    override fun getDeploymentService(project: Project): DeploymentService {
        return DeploymentService.getInstance()
    }
}
