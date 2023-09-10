package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.idea.run.*
import com.android.utils.ILogger
import com.intellij.openapi.project.Project

/**
 * Compat for Android Studio Deployer API
 */
interface IAsDeployerCompat {

    fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider

    fun getDevices(project: Project): List<IDevice>?

    fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller

    fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: InstallMode,
    ): Boolean

    fun makeDebuggerRedefiners(project: Project, device: IDevice, fallback: Boolean): Map<Int, ClassRedefiner>

    fun optimisticSwap(
        installer: Installer,
        redefiners: Map<Int, ClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: OverlayUpdate,
        adb: AdbClient,
        logger: ILogger,
    ): OverlayId

    fun toApkProvider(apkInfos: List<ApkInfo>): ApkProvider

    fun getDisableMessage(project: Project): String?

    fun getDeploymentService(project: Project): DeploymentService

    companion object {
        const val MIN_DEVICE_API = 30
    }
}

