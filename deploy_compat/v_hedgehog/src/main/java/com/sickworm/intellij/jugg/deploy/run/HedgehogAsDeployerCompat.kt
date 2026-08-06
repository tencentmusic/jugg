package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.execution.common.DeployableToDevice
import com.intellij.execution.configurations.RunConfigurationBase

/**
 * Android Studio Giraffe
 */
open class HedgehogAsDeployerCompat: GiraffeAsDeployerCompat() {

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeployableToDevice.KEY, true)
    }
}
