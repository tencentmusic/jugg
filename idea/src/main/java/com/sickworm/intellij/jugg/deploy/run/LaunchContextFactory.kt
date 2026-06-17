package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.IdeService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.direct.InstallerDeviceAbiResolver
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.ide.bean.JuggSettings

/**
 * Creates a complete deploy launch context, including AS install session and direct overlay runtime facts.
 */
class LaunchContextFactory(
    private val project: Project,
    private val installPathProvider: Computable<String>,
    private val asDeployerCompat: IAsDeployerCompat,
    private val deviceAdbFactory: (IDevice, Logger) -> IDeviceAdb,
    private val logger: Logger,
) {

    fun create(
        device: IDevice,
        exceptOverlayIds: Map<String, String>,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
        isDeviceReadyDeploy: Boolean,
        isAllowDirectOverlayDeploy: Boolean,
        forceDirectOverlayDeploy: Boolean = false,
    ): LaunchContext {
        val deviceAdb = deviceAdbFactory(device, logger)
        val installersRoot = installPathProvider.compute()
        val deployLogger = AdbLogWrapper(logger)
        val ideService = IdeService(project)
        val installSession = asDeployerCompat.createInstallSession(
            installersRoot,
            device,
            deployLogger,
            onPrompt = { message ->
                if (compileUiHandler.shouldAutoConfirmDeployPrompt(message)) {
                    deployLogger.warning("Deploy prompt auto-confirmed by compile ui handler: %s", message)
                    true
                } else {
                    ideService.prompt(message)
                }
            },
            onMessage = { message ->
                compileUiHandler.onDeployUiMessage(message)
                ideService.message(message)
            },
        )
        return LaunchContext(
            device = device,
            deviceAdb = deviceAdb,
            installersRoot = installersRoot,
            installSession = installSession,
            deviceAbi = InstallerDeviceAbiResolver.resolve(deviceAdb),
            exceptOverlayIds = exceptOverlayIds,
            isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
            compileUiHandler = compileUiHandler,
            isDirectOverlaySettingsEnabled = JuggSettings.isEnableDirectOverlayDeploy,
            isDeviceReadyDeploy = isDeviceReadyDeploy,
            isAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy,
            forceDirectOverlayDeploy = forceDirectOverlayDeploy,
        )
    }
}
