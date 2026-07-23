package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.ClassRedefiner
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.MetricsRecorder
import com.android.tools.deployer.OptimisticApkSwapper
import com.android.tools.deployer.common.AdbClient
import com.android.tools.deployer.common.ApplicationDumper
import com.android.tools.deployer.common.DeployerException
import com.android.tools.deployer.common.DeployerOption
import com.android.tools.deployer.common.InstallOptions
import com.android.tools.deployer.common.Installer
import com.android.tools.deployer.common.OverlayId
import com.android.tools.deployer.common.UIService
import com.android.tools.deployer.install.ApkInstaller
import com.android.tools.deployer.install.InstallMode
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.ApkParser
import com.android.tools.deployer.model.App
import com.android.tools.deployer.model.AppState
import com.android.tools.deployer.model.DeploymentPlan
import com.android.tools.idea.adb.AdbService
import com.android.tools.idea.execution.common.DeployableToDevice
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeploymentService
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.io.File
import java.nio.file.Path

/**
 * Android Studio Quail compatibility layer.
 */
open class QuailAsDeployerCompat : IAsDeployerCompat {

    private val deployOptions = DeployerOption.Builder().build()

    private val metrics = MetricsRecorder()

    override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider {
        return project.getProjectSystem().getApkProvider(config)!!
    }

    override fun getSelectedDevices(project: Project): List<IDevice>? {
        val deployTarget = DeployTargetContext().currentDeployTargetProvider.getDeployTarget(project)
        return deployTarget.launchDevices(project).ifReady?.takeIf { it.isNotEmpty() }
    }

    override fun getConnectedDevices(project: Project): List<IDevice>? {
        val adb = AndroidSdkUtils.getAdb(project)?.toPath() ?: return null
        val debugBridge = AdbService.getInstance().getDebugBridge(adb.toFile())
        return debugBridge.get().devices?.toList()
    }

    override fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession {
        val mode = AdbInstaller.Mode.DAEMON
        val installer = AdbInstaller(installersFolder, createAdbClient(device, logger), metrics.deployMetrics, logger, mode)
        return JuggInstallSession(installer, installer.version, onPrompt, onMessage)
    }

    private fun createInstallOptions(device: IDevice, applicationId: String): InstallOptions {
        val options = InstallOptions.builder().setAllowDebuggable()
        if (device.supportsFeature(IDevice.HardwareFeature.EMBEDDED)) {
            options.setGrantAllPermissions()
        }
        if (device.version.isGreaterOrEqualThan(28)) {
            options.setInstallFullApk()
        }
        if (device.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.N)) {
            options.setDontKill()
        }
        options.setSkipVerification(device, applicationId)
        return options.build()
    }

    override fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        val apkInstaller = ApkInstaller(
            createAdbClient(device, logger),
            session.toQuailUiService(),
            session.rawInstaller as Installer,
            logger,
        )
        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        val deploymentPlan = DeploymentPlan(app, AppState(""))
        val noDeltaFallback = DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build()
        return apkInstaller.install(
            deploymentPlan,
            noDeltaFallback,
            createInstallOptions(device, packageName),
            installMode.toQuailInstallMode(),
            metrics.deployMetrics,
        )
    }

    override fun getInstallMode(): JuggInstallSession.Mode {
        return JuggInstallSession.Mode.DELTA
    }

    override fun makeDebuggerRedefiners(
        project: Project,
        device: IDevice,
        fallback: Boolean,
    ): Map<Int, ClassRedefiner> {
        return ImmutableMap.of()
    }

    override fun optimisticSwap(
        session: JuggInstallSession,
        redefiners: Map<Int, ClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: JuggOverlayUpdate,
        device: IDevice,
        logger: ILogger,
        isPushOverlayOnly: Boolean,
    ): JuggOverlayId {
        val swapper = OptimisticApkSwapper(
            session.rawInstaller as Installer,
            redefiners,
            argRestart,
            deployOptions,
            metrics,
        )
        val swapResult = swapper.optimisticSwap(packageName, pids, arch, overlayUpdate.raw as OptimisticApkSwapper.OverlayUpdate)
        return swapResult.overlayId.toJuggOverlayId()
    }

    override fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState {
        if (device == null) {
            return IdeDeployState.deviceNotConnected
        }
        if (packageName == null) {
            return IdeDeployState.canNotDetectApplicationId
        }
        return if (device.state == IDevice.DeviceState.UNAUTHORIZED) {
            IdeDeployState.deviceNotAuthorized
        } else if (!device.version.isGreaterOrEqualThan(IAsDeployerCompat.MIN_DEVICE_API)) {
            IdeDeployState.incompatibleDeviceApiLevel
        } else if (findClient(device, packageName).isEmpty()) {
            IdeDeployState.appNotRunningOrNotDebuggable
        } else {
            IdeDeployState.ok
        }
    }

    override fun getDeploymentService(project: Project): DeploymentService {
        return DeploymentService.getInstance()
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return ApkParser.parsePaths(paths)
    }

    override fun getPackageName(apks: List<Apk>): String {
        return ApplicationDumper.getPackageName(apks)
    }

    override fun createBaseOverlayId(apks: List<Apk>): JuggOverlayId {
        return OverlayId(apks).toJuggOverlayId()
    }

    override fun buildOverlayId(base: JuggOverlayId, addedFiles: List<JuggOverlayFile>): JuggOverlayId {
        val builder = OverlayId.builder(base.raw as OverlayId)
        addedFiles.forEach { builder.addOverlayFile(it.path, it.checksum) }
        return builder.build().toJuggOverlayId()
    }

    override fun createOverlayUpdate(
        cachedDump: JuggDeploymentCacheEntry,
        dexOverlays: DexComparator.ChangedClasses,
        fileOverlays: Map<ApkEntry, ByteString>,
    ): JuggOverlayUpdate {
        val raw = OptimisticApkSwapper.OverlayUpdate(
            cachedDump.raw as com.android.tools.deployer.common.DeploymentCacheDatabase.Entry,
            dexOverlays,
            fileOverlays,
        )
        return JuggOverlayUpdate(cachedDump, dexOverlays, fileOverlays, raw)
    }

    override fun dumpApks(session: JuggInstallSession, apks: List<Apk>): List<Apk> {
        return ApplicationDumper(session.rawInstaller as Installer).dump(apks).apks
    }

    override fun remoteApkNotFound(): JuggDeployerException {
        return DeployerException.remoteApkNotFound().toJuggDeployerException()
    }

    override fun overlayIdMismatch(): JuggDeployerException {
        return DeployerException.overlayIdMismatch().toJuggDeployerException()
    }

    override fun apiNotSupported(): JuggDeployerException {
        return DeployerException.apiNotSupported().toJuggDeployerException()
    }

    override fun wrapDeployerException(e: Throwable): JuggDeployerException? {
        return (e as? DeployerException)?.toJuggDeployerException()
    }

    override fun createDeploymentCacheEntry(apks: List<Apk>, overlayId: JuggOverlayId): JuggDeploymentCacheEntry {
        val database = com.android.tools.deployer.common.DeploymentCacheDatabase(1)
        database.store(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME, apks, overlayId.raw as OverlayId)
        val entry = database.get(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME)
        return JuggDeploymentCacheEntry(entry, apks, overlayId)
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeployableToDevice.KEY, true)
    }

    override fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
        val client = AndroidDebugClientReadyWaiter().waitForWaitingDebuggerClient(device, packageName)
        AndroidStudioDebuggerAttachStarter().attachExistingProcess(project, client)
    }

    override fun getSuggestRunConfigurations(
        existsRunConfigNames: List<String>,
        project: Project,
        logger: Logger,
        isNeedDefaultRunConfig: Boolean,
    ): List<SuggestRunConfiguration> {
        val result = mutableListOf<SuggestRunConfiguration>()
        val existsModuleForRunConfig = existsRunConfigNames.map {
            SuggestRunConfiguration.getModuleNameByRunConfigName(it)
        }.toSet()

        val androidConfigSettings = RunManager.getInstance(project).allSettings
            .filter { it.type is AndroidRunConfigurationType }
        androidConfigSettings.forEach { configSettings ->
            val suggestRunConfig = getSuggestRunConfiguration(configSettings, project, logger) ?: return@forEach
            if (suggestRunConfig.moduleName !in existsModuleForRunConfig) {
                result.add(suggestRunConfig)
            }
        }

        if (result.isEmpty() && existsRunConfigNames.isEmpty() && isNeedDefaultRunConfig) {
            return listOf(SuggestRunConfiguration.DEFAULT)
        }
        return result
    }

    override fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo? {
        val gradleAndroidModel = runCatching { GradleAndroidModel.get(module) }.getOrNull() ?: return null
        val androidFacet = AndroidFacet.getInstance(module)
        val buildVariant = androidFacet?.properties?.SELECTED_BUILD_VARIANT
            .takeUnless { it.isNullOrEmpty() }
            ?: gradleAndroidModel.selectedVariant.name
        val androidTestPackageInfo = readAndroidTestPackageInfo(gradleAndroidModel)

        val compileTarget = gradleAndroidModel.androidProject.compileTarget
        val result = IdeModuleInfo(
            baseDir = gradleAndroidModel.rootDirPath,
            buildToolsVersion = gradleAndroidModel.androidProject.buildToolsVersion,
            compileVersion = compileTarget.removePrefix("android-"),
            minSdkVersion = gradleAndroidModel.selectedVariant.minSdkVersion.apiString,
            kotlinJvmTarget = gradleAndroidModel.readLanguageLevel("getTargetLanguageLevel"),
            kotlinFreeCompilerArgs = emptyList(),
            javaSourceCompatibility = gradleAndroidModel.readLanguageLevel("getJavaSourceLanguageLevel"),
            javaTargetCompatibility = gradleAndroidModel.readLanguageLevel("getTargetLanguageLevel"),
            minifyEnabled = null,
            buildVariant = buildVariant,
            manifestRelativePath = androidFacet?.properties?.MANIFEST_FILE_RELATIVE_PATH,
            brokenFields = emptyList(),
            androidTestApplicationId = androidTestPackageInfo.applicationId,
            androidTestInstrumentationTargetPackage = androidTestPackageInfo.instrumentationTargetPackage,
        )
        IdeAndroidTestPackageReader.traceReadResult(
            logger = logger,
            moduleName = module.name,
            isSafeMode = isSafeMode,
            buildVariant = buildVariant,
            gradleAndroidModel = gradleAndroidModel,
            packageInfo = androidTestPackageInfo,
            brokenFields = result.brokenFields,
        )
        return result
    }

    private fun getSuggestRunConfiguration(
        settings: RunnerAndConfigurationSettings,
        project: Project,
        logger: Logger,
    ): SuggestRunConfiguration? {
        return try {
            val runConfig = settings.configuration as AndroidRunConfiguration
            val module = runConfig.modules.firstOrNull() ?: return null
            val gradleAndroidModel = GradleAndroidModel.get(module) ?: return null
            val moduleName = gradleAndroidModel.moduleName.split('.').last()
            val taskName = gradleAndroidModel.mainArtifact.assembleTaskName
            val compileCommand = "./gradlew :$moduleName:$taskName"
            val projectPath = project.basePath ?: return null
            val buildType = gradleAndroidModel.selectedVariant.buildType
            val productFlavorPath = gradleAndroidModel.selectedVariant.productFlavors
                .joinToString("") { it.replaceFirstChar { char -> char.uppercaseChar() } }
                .replaceFirstChar { it.lowercaseChar() }
                .takeIf { it.isNotEmpty() }
                ?.let { "$it/" }
                ?: ""
            val moduleRelativePath = gradleAndroidModel.rootDirPath.relativeTo(File(projectPath)).path
            val apkPath = moduleRelativePath.replace("\\", "/") + "/build/outputs/apk/$productFlavorPath$buildType/*.apk"
            SuggestRunConfiguration(moduleName, compileCommand, apkPath)
        } catch (e: Exception) {
            logger.debug("getSuggestRunConfiguration for ${settings.name} error, ignore", e)
            null
        }
    }

    private fun readAndroidTestPackageInfo(gradleAndroidModel: Any?): IdeAndroidTestPackageInfo {
        return IdeAndroidTestPackageReader.read(gradleAndroidModel)
    }

    private fun findClient(device: IDevice, packageName: String): List<Client> {
        val clazz = Class.forName("com.android.tools.idea.run.DeploymentApplicationService")
        val instance = clazz.getMethod("getInstance").invoke(null)
        val method = clazz.getMethod("findClient", IDevice::class.java, String::class.java)
        @Suppress("UNCHECKED_CAST")
        return method.invoke(instance, device, packageName) as List<Client>
    }

    private fun GradleAndroidModel.readLanguageLevel(methodName: String): String? {
        return runCatching {
            val languageLevel = javaClass.getMethod(methodName).invoke(this) ?: return null
            val javaVersion = languageLevel.javaClass.getMethod("toJavaVersion").invoke(languageLevel)
            javaVersion?.toString()
        }.getOrNull()
    }

    private fun createAdbClient(device: IDevice, logger: ILogger): AdbClient {
        return AdbClient(device, logger)
    }

    private fun JuggInstallSession.toQuailUiService(): UIService {
        val session = this
        return object : UIService {
            override fun prompt(message: String): Boolean = session.prompt(message)

            override fun message(message: String) = session.message(message)
        }
    }

    private fun JuggInstallSession.Mode.toQuailInstallMode(): InstallMode {
        return when (this) {
            JuggInstallSession.Mode.DELTA -> InstallMode.DELTA
            JuggInstallSession.Mode.DELTA_NO_SKIP -> InstallMode.DELTA_NO_SKIP
            JuggInstallSession.Mode.FULL -> InstallMode.FULL
        }
    }

    private fun OverlayId.toJuggOverlayId(): JuggOverlayId {
        val overlayFiles = overlayContents.allFiles().map {
            JuggOverlayFile(it, overlayContents.getFileChecksum(it))
        }
        return JuggOverlayId(this, sha, isBaseInstall, overlayFiles)
    }

    private fun DeployerException.toJuggDeployerException(): JuggDeployerException {
        return JuggDeployerException(error.ordinal, message, details, this)
    }

    private companion object {
        const val MEMORY_DEVICE_SERIAL = "memory"
        const val MEMORY_PACKAGE_NAME = "entry"
    }
}
