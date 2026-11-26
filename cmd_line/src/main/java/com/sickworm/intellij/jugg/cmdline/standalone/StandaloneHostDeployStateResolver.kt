package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IHostDeployStateResolver
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState

/** Resolves deploy readiness from standalone adb state. */
class StandaloneHostDeployStateResolver(
    private val environmentProvider: () -> IDeployHost,
    private val logger: Logger,
) : IHostDeployStateResolver {
    override fun resolve(device: IDevice?, packageName: String?): IdeDeployState {
        if (device == null || !device.isOnline) return IdeDeployState.deviceNotConnected
        if (device.version.apiLevel <= 0) return IdeDeployState.unknownDeviceApiLevel
        if (device.version.apiLevel < IAsDeployerCompat.MIN_DEVICE_API) return IdeDeployState.incompatibleDeviceApiLevel
        if (packageName.isNullOrBlank()) return IdeDeployState.canNotDetectApplicationId
        return runCatching {
            val adb = environmentProvider().createDeviceAdb(device, logger)
            if (adb.getPids(packageName).isEmpty()) IdeDeployState.appNotRunningOrNotDebuggable else IdeDeployState.ok
        }.onFailure { logger.debug("Resolve standalone deploy state failed", it) }
            .getOrDefault(IdeDeployState.unexpectedException)
    }
}
