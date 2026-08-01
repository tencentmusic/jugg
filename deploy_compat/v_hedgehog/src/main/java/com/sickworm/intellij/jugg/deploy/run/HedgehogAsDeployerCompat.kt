package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.execution.common.DeployableToDevice
import com.android.tools.idea.run.DeploymentService
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project

/**
 * Android Studio Giraffe
 */
open class HedgehogAsDeployerCompat: GiraffeAsDeployerCompat() {

    override fun getDeploymentService(project: Project): DeploymentService {
        return DeploymentService.getInstance()
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeployableToDevice.KEY, true)
    }
}
