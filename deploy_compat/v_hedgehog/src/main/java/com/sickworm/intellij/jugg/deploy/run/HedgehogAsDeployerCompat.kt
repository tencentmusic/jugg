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
import java.lang.reflect.Field

/**
 * Android Studio Giraffe
 */
class HedgehogAsDeployerCompat: GiraffeAsDeployerCompat() {

    override fun getDisableMessage(project: Project): String? {
        val disableMessage = doGetDisableMessage(project) ?: return null
        return getToolTipField().get(disableMessage) as? String
    }

    private var toolTipField: Field? = null

    private fun getToolTipField(): Field {
        toolTipField?.let { return it }
        val toolTipField = BaseAction.DisableMessage::class.java.getDeclaredField("myTooltip")
        toolTipField.isAccessible = true
        this.toolTipField = toolTipField
        return toolTipField
    }

    /**
     * @see [BaseAction.getDisableMessage]
     */
    private fun doGetDisableMessage(project: Project): BaseAction.DisableMessage? {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return BaseAction.DisableMessage(
            BaseAction.DisableMessage.DisableMode.INVISIBLE, "no available supported configuration",
            "all configuration is not supported"
        )

        val packageName = (selectedRunConfig as AppRunConfiguration).appId ?: ""
        val selectedExecutionTarget = ExecutionTargetManager.getInstance(project)
            .getTargetsFor(selectedRunConfig)
            .find { it is AndroidExecutionTarget } as? AndroidExecutionTarget
            ?: return BaseAction.DisableMessage(BaseAction.DisableMessage.DisableMode.DISABLED, "unsupported execution target", "unsupported execution target")

        val devices = selectedExecutionTarget.runningDevices
        return if (devices.isEmpty()) {
            BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED,
                "devices not connected",
                "the selected devices are not connected"
            )
        } else if (devices.stream().anyMatch { d: IDevice ->
                d.state == IDevice.DeviceState.UNAUTHORIZED
            }) {
            BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED,
                "device not authorized",
                "the selected device is not authorized"
            )
        } else if (devices.stream().anyMatch { d: IDevice ->
                !d.version.isGreaterOrEqualThan(26)
            }) {
            BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED,
                "incompatible device API level",
                "its API level is lower than 26"
            )
        } else if (devices.stream().allMatch { d: IDevice ->
                DeploymentApplicationService.instance.findClient(d, packageName)
                    .isEmpty()
            }) {
            BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED,
                "app not detected",
                "the app is not yet running or not debuggable"
            )
        } else {
            return null
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
