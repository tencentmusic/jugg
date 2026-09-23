package com.sickworm.intellij.jugg.deploy.run;

import com.android.ddmlib.IDevice;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.idea.adblib.AdbLibApplicationService;
import com.android.utils.ILogger;

import java.util.Optional;

/**
 * Android Studio Rabbit compatibility layer.
 */
public class RabbitAsDeployerCompat extends QuailAsDeployerCompat {

    @Override
    protected AdbClient createAdbClient(IDevice device, ILogger logger) {
        var session = AdbLibApplicationService.getInstance().getSession();
        return new AdbClient(new DeviceHolder(device, Optional.empty()), logger, session);
    }
}
