package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.AppAbiCache
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.hotreload.RootlessCompatPending

/**
 * Runtime context shared by deploy tasks, deployer, and direct overlay transport for one deploy run.
 */
class LaunchContext(
    val device: IDevice,
    val deviceAdb: IDeviceAdb,
    val installersRoot: String,
    val installSession: JuggInstallSession,
    val deployDebugger: IDeployDebugger,
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
    private val deployHost: IDeployHost? = null,
    private val appSandboxExecutors: MutableMap<String, AppSandboxExecutor> = mutableMapOf(),
    internal val appAbiCache: AppAbiCache = AppAbiCache(),
    /**
     * Rootless compat requests staged in this run. The app owns the commit, so deploy state must not
     * advance until it reports the import result of every request listed here.
     */
    val rootlessCompatPending: MutableList<RootlessCompatPending> = mutableListOf(),
) {
    val applyChangesExecutor: IApplyChangesExecutor
        get() = installSession.applyChangesExecutor

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

    fun runCustomApkInstall(applicationId: String, logger: Logger) {
        val host = deployHost
            ?: throw UnsupportedOperationException("Custom APK install scripts require a deploy host")
        host.runCustomApkInstall(customApkInstallScript, applicationId, this, logger)
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
            deployDebugger = deployDebugger,
            deviceAbi = deviceAbi,
            exceptOverlayIds = exceptOverlayIds,
            isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
            compileUiHandler = compileUiHandler,
            isDirectOverlaySettingsEnabled = isDirectOverlaySettingsEnabled,
            isDeviceReadyDeploy = isDeviceReadyDeploy,
            isAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy,
            forceDirectOverlayDeploy = forceDirectOverlayDeploy,
            customApkInstallScript = customApkInstallScript,
            deployHost = deployHost,
            appSandboxExecutors = appSandboxExecutors,
            appAbiCache = appAbiCache,
            rootlessCompatPending = rootlessCompatPending,
        )
    }
}
