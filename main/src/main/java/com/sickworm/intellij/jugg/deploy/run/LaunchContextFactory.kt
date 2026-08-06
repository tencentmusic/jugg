package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.direct.InstallerDeviceAbiResolver
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper

/**
 * Creates a host-neutral deploy launch context and delegates real host differences to the environment.
 */
class LaunchContextFactory(private val environment: IDeployHost, private val logger: Logger) {

    fun create(
        device: IDevice, exceptOverlayIds: Map<String, String>, isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler, isDeviceReadyDeploy: Boolean, isAllowDirectOverlayDeploy: Boolean,
        forceDirectOverlayDeploy: Boolean = false,
    ): LaunchContext {
        val deviceAdb = environment.createDeviceAdb(device, logger)
        val installersRoot = environment.installersRoot()
        val deployLogger = AdbLogWrapper(logger)
        val installSession = environment.applyChangesExecutor.createInstallSession(
            installersRoot, device, deployLogger,
            onPrompt = { message -> environment.confirmDeployPrompt(message, compileUiHandler, logger) },
            onMessage = { message -> environment.onDeployMessage(message, compileUiHandler) },
        )
        val deployDebugger = environment.createDeployDebugger(installSession.applyChangesExecutor)
        return LaunchContext(
            device = device, deviceAdb = deviceAdb, installersRoot = installersRoot, installSession = installSession,
            deployDebugger = deployDebugger,
            deviceAbi = InstallerDeviceAbiResolver.resolve(deviceAdb), exceptOverlayIds = exceptOverlayIds,
            isSkipExceptOverlayCheck = isSkipExceptOverlayCheck, compileUiHandler = compileUiHandler,
            isDirectOverlaySettingsEnabled = environment.isDirectOverlayEnabled, isDeviceReadyDeploy = isDeviceReadyDeploy,
            isAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy,
            forceDirectOverlayDeploy = forceDirectOverlayDeploy,
        )
    }
}
