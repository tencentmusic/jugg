package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.AppAbiCache
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb

/**
 * Runtime context shared by deploy tasks, deployer, and direct overlay transport for one deploy run.
 */
class LaunchContext(
    val device: IDevice,
    val deviceAdb: IDeviceAdb,
    val installersRoot: String,
    val installSession: JuggInstallSession,
    val deviceAbi: String,
    val exceptOverlayIds: Map<String, String>,
    val isSkipExceptOverlayCheck: Boolean,
    val compileUiHandler: CompileUiHandler,
    val isDirectOverlaySettingsEnabled: Boolean,
    val isDeviceReadyDeploy: Boolean,
    val isAllowDirectOverlayDeploy: Boolean,
    val forceDirectOverlayDeploy: Boolean = false,
    /** Non-blank when ordinary app APK installation uses a project script. */
    val customApkInstallScript: String = "",
    private val appSandboxExecutors: MutableMap<String, AppSandboxExecutor> = mutableMapOf(),
    internal val appAbiCache: AppAbiCache = AppAbiCache(),
) {
    val isDirectOverlayEnabled: Boolean
        get() = isDirectOverlaySettingsEnabled &&
            (forceDirectOverlayDeploy || !isDeviceReadyDeploy) &&
            isAllowDirectOverlayDeploy

    var launchApp: Boolean = false
    var killBeforeLaunch: Boolean = false

    fun getAppSandboxExecutor(packageName: String, logger: Logger): AppSandboxExecutor {
        return synchronized(appSandboxExecutors) {
            appSandboxExecutors.getOrPut(packageName) {
                AppSandboxExecutor(deviceAdb, packageName, logger)
            }
        }
    }

    fun logDirectOverlayEnabled(logger: Logger) {
        logger.debug(
            "Direct overlay enabled=$isDirectOverlayEnabled: " +
                "settingsEnabled=$isDirectOverlaySettingsEnabled, " +
                "isDeviceReadyDeploy=$isDeviceReadyDeploy, " +
                "isAllowedByCaller=$isAllowDirectOverlayDeploy, " +
                "forceDirectOverlayDeploy=$forceDirectOverlayDeploy",
        )
    }

    fun withSkipExceptOverlayCheck(isSkipExceptOverlayCheck: Boolean): LaunchContext {
        return LaunchContext(
            device = device,
            deviceAdb = deviceAdb,
            installersRoot = installersRoot,
            installSession = installSession,
            deviceAbi = deviceAbi,
            exceptOverlayIds = exceptOverlayIds,
            isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
            compileUiHandler = compileUiHandler,
            isDirectOverlaySettingsEnabled = isDirectOverlaySettingsEnabled,
            isDeviceReadyDeploy = isDeviceReadyDeploy,
            isAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy,
            forceDirectOverlayDeploy = forceDirectOverlayDeploy,
            customApkInstallScript = customApkInstallScript,
            appSandboxExecutors = appSandboxExecutors,
            appAbiCache = appAbiCache,
        )
    }
}
