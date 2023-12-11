package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.Apk
import com.android.tools.idea.run.*
import com.android.utils.ILogger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/**
 * Compat for Android Studio Deployer API
 */
interface IAsDeployerCompat {

    fun isSupportsSyncCallback(): Boolean

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

    fun getIdeDeployStateResult(project: Project, device: IDevice): IdeDeployState

    fun getDeploymentService(project: Project): DeploymentService

    fun parseApks(paths: List<String>): List<Apk>

    fun getModuleManager(project: Project): ModuleManager {
        // ModuleManager rewrite by Kotlin after Android Studio Giraffe
        // which cause "java.lang.NoSuchFieldError: Companion" before Android Studio Giraffe

        val companionField = try {
            ModuleManager::class.java.getDeclaredField("Companion")
        } catch (e: NoSuchFieldException) {
            null
        }

        return if (companionField == null) {
            // before Android Studio Giraffe
            val getInstanceMethod = ModuleManager::class.java.getDeclaredMethod("getInstance", Project::class.java)
            getInstanceMethod.invoke(null, project) as ModuleManager
        } else {
            // after Android Studio Giraffe
            val companion = companionField.get(null)
            val getInstanceMethod = companion.javaClass.getDeclaredMethod("getInstance", Project::class.java)
            getInstanceMethod.invoke(companion, project) as ModuleManager
        }
    }

    companion object {
        const val MIN_DEVICE_API = 30
    }
}

