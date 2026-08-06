package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.ILogger
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

    /**
     * Returns devices selected in the IDE that are already running.
     * This method must not boot virtual devices.
     */
    fun getSelectedDevices(project: Project): List<IDevice>?

    fun getConnectedDevices(project: Project): List<IDevice>?

    fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession

    fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean

    fun getInstallMode(): JuggInstallSession.Mode

    fun makeDebuggerRedefiners(project: Project, device: IDevice, fallback: Boolean): Map<Int, JuggClassRedefiner>

    fun optimisticSwap(
        session: JuggInstallSession,
        redefiners: Map<Int, JuggClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: JuggOverlayUpdate,
        device: IDevice,
        logger: ILogger,
        isPushOverlayOnly: Boolean,
    ): JuggOverlayId

    fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState

    fun parseApks(paths: List<String>): List<Apk>

    fun getPackageName(apks: List<Apk>): String

    fun createBaseOverlayId(apks: List<Apk>): JuggOverlayId

    fun buildOverlayId(base: JuggOverlayId, addedFiles: List<JuggOverlayFile>): JuggOverlayId

    fun createOverlayUpdate(
        cachedDump: JuggDeploymentCacheEntry,
        dexOverlays: DexComparator.ChangedClasses,
        fileOverlays: Map<ApkEntry, ByteString>,
    ): JuggOverlayUpdate

    fun dumpApks(session: JuggInstallSession, apks: List<Apk>): List<Apk>

    fun remoteApkNotFound(): JuggDeployerException

    fun overlayIdMismatch(): JuggDeployerException

    fun apiNotSupported(): JuggDeployerException

    fun wrapDeployerException(e: Throwable): JuggDeployerException?

    fun createDeploymentCacheEntry(apks: List<Apk>, overlayId: JuggOverlayId): JuggDeploymentCacheEntry

    fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>)

    fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
        throw UnsupportedOperationException("Jugg Debug is not supported in this Android Studio version.")
    }

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
    val androidTestApplicationId: String? = null,
    val androidTestInstrumentationTargetPackage: String? = null,
    val buildDir: File? = null,
)

fun Any?.readAndroidBuildFolderCompat(): File? {
    val model = this ?: return null
    return runCatching {
        val androidProject = model.javaClass.getMethod("getAndroidProject").invoke(model) ?: return@runCatching null
        androidProject.javaClass.getMethod("getBuildFolder").invoke(androidProject) as? File
    }.getOrNull()
}
