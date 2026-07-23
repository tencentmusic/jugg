package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.editor.DeployTarget
import com.android.tools.idea.run.editor.DeployTargetContext
import com.intellij.openapi.project.Project

open class MeerkatAsDeployerCompat: IguanaAsDeployerCompat() {

    override fun getSelectedDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget: DeployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
        val selectedDevices = deployTarget.getAndroidDevices(project)
        val readyDevices = selectedDevices.mapNotNull { it.ddmlibDevice }
        return readyDevices.takeIf {
            it.isNotEmpty() && it.size == selectedDevices.size
        }
    }
}
