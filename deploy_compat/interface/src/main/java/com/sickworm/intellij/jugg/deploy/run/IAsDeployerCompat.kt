package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.model.Apk
import com.android.tools.idea.run.*
import com.android.utils.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import java.io.File

/**
 * Compat for Android Studio Deployer API
 */
interface IAsDeployerCompat {

    fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider

    fun getSelectedDevices(project: Project): List<IDevice>?

    fun getConnectedDevices(project: Project): List<IDevice>?

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
        overlayUpdate: JuggOverlayUpdate,
        adb: AdbClient,
        logger: ILogger,
        isPushOverlayOnly: Boolean,
    ): OverlayId

    fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState

    fun getDeploymentService(project: Project): DeploymentService

    fun parseApks(paths: List<String>): List<Apk>

    fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>)

    /**
     * Get suggest run configuration from [AndroidRunConfigurationType]
     */
    fun getSuggestRunConfigurations(existsRunConfigNames: List<String>, project: Project, logger: Logger, isNeedDefaultRunConfig: Boolean): List<SuggestRunConfiguration>

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

    fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo?

    companion object {
        const val ANDROID_11_API = 30
        @Suppress("MemberVisibilityCanBePrivate")
        const val ANDROID_8_API = 26
        var MIN_DEVICE_API = ANDROID_11_API // Android 8
            private set

        fun updateMinApi(isEnableCompatDeploy: Boolean) {
            MIN_DEVICE_API = if (isEnableCompatDeploy) {
                ANDROID_8_API
            } else{
                ANDROID_11_API
            }
        }
    }
}

data class IdeModuleInfo(
    val baseDir: File?,
    val buildToolsVersion: String?,
    val compileVersion: String?,
    val minSdkVersion: String?,
    val kotlinJvmTarget: String?,
    val kotlinFreeCompilerArgs: List<String>?,
    val javaSourceCompatibility: String?,
    val javaTargetCompatibility: String?,
    val minifyEnabled: String?,
    val buildVariant: String,
    val manifestRelativePath: String?,
    val brokenFields: List<String>,
)
