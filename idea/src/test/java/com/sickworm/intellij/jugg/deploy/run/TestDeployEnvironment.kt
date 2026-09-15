package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.mockito.Mockito

/** Test host for shared deployment behavior without IDEA or ddmlib conversions. */
class TestDeployEnvironment(
    override val applyChangesExecutor: IApplyChangesExecutor = Mockito.mock(IApplyChangesExecutor::class.java),
    private val adb: IDeviceAdb = Mockito.mock(IDeviceAdb::class.java),
    private val installersRoot: String = "/tmp/installers",
    override val isDirectOverlayEnabled: Boolean = false,
    private val adbFactory: ((IDevice, Logger) -> IDeviceAdb)? = null,
    private val customApkInstaller: ((String, String, LaunchContext, Logger) -> Unit)? = null,
) : IDeployHost {
    override fun installersRoot(): String = installersRoot
    override fun createDeployDebugger(applyChangesExecutor: IApplyChangesExecutor): IDeployDebugger = NoDeployDebugger
    override fun createDeviceAdb(device: IDevice, logger: Logger): IDeviceAdb = adbFactory?.invoke(device, logger) ?: adb
    override fun confirmDeployPrompt(message: String, uiHandler: CompileUiHandler, logger: Logger): Boolean = true
    override fun onDeployMessage(message: String, uiHandler: CompileUiHandler) = uiHandler.onDeployUiMessage(message)
    override fun notify(message: String) = Unit
    override fun clearAppData(device: IDevice, packageName: String, logger: Logger) = Unit
    override fun hasRelaunchActivityIssues(adb: IDeviceAdb, logger: Logger): Boolean {
        return PlatformApi.isHasRelaunchActivityIssues(adb, logger)
    }
    override fun launchAndroidTest(
        request: JuggDeployRunTaskRequest, data: JuggDeployData, launchResult: LaunchResult,
    ): LaunchResult? = null
    override fun onDeployTimeout(device: IDevice, logger: Logger) = Unit
    override fun uninstall(device: IDevice, packageName: String, logger: Logger) = Unit
    override fun runCustomApkInstall(
        script: String,
        applicationId: String,
        launchContext: LaunchContext,
        logger: Logger,
    ) {
        customApkInstaller?.invoke(script, applicationId, launchContext, logger)
            ?: super.runCustomApkInstall(script, applicationId, launchContext, logger)
    }
}
