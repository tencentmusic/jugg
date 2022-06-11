package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeviceCount
import com.android.tools.idea.run.DeviceFutures
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.utils.ILogger
import com.intellij.openapi.project.Project
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.android.facet.AndroidFacet

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
}

