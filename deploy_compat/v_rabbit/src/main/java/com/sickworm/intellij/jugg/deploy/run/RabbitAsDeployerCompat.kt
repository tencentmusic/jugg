package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.common.AdbClient
import com.android.tools.deployer.common.DeviceHolder
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.utils.ILogger
import java.util.Optional

/**
 * Android Studio Rabbit compatibility layer.
 */
open class RabbitAsDeployerCompat : QuailAsDeployerCompat() {

    override fun createAdbClient(device: IDevice, logger: ILogger): AdbClient {
        val session = AdbLibApplicationService.instance.session
        return AdbClient(DeviceHolder(device, Optional.empty()), logger, session)
    }
}
