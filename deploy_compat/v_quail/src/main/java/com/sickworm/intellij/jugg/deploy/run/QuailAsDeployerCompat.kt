package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.android.sdklib.AndroidVersion
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.ClassRedefiner
import com.sickworm.intellij.jugg.deploy.api.DexComparator
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
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.android.tools.deployer.model.App
import com.android.tools.deployer.model.AppState
import com.android.tools.deployer.model.DeploymentPlan
import com.android.tools.idea.adb.AdbService
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.execution.common.DeployableToDevice
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.run.editor.DeployTargetContext
import com.sickworm.intellij.jugg.deploy.api.ILogger
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

    private val deployApiConverter = QuailDeployApiConverter()

    private val deployOptions = DeployerOption.Builder().build()

    private val metrics = MetricsRecorder()

    override fun getSelectedDevices(project: Project): List<IDevice>? {
        val deployTarget = DeployTargetContext().currentDeployTargetProvider.getDeployTarget(project)
        val selectedDevices = deployTarget.getAndroidDevices(project)
        val readyDevices = selectedDevices.mapNotNull { it.ddmlibDevice }.map(::toJuggDevice)
        return readyDevices.takeIf {
            it.isNotEmpty() && it.size == selectedDevices.size
        }
    }

    override fun getConnectedDevices(project: Project): List<IDevice>? {
        val adb = AndroidSdkUtils.getAdb(project)?.toPath() ?: return null
        val debugBridge = AdbService.getInstance().getDebugBridge(adb.toFile())
        return debugBridge.get().devices?.map(::toJuggDevice)
    }

    override fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession {
        val mode = AdbInstaller.Mode.DAEMON
        val studioLogger = toStudioLogger(logger)
        val installer = AdbInstaller(installersFolder, createAdbClient(toStudioDevice(device), studioLogger), metrics.deployMetrics, studioLogger, mode)
        return JuggInstallSession(installer, installer.version, onPrompt, onMessage)
    }

    private fun createInstallOptions(device: IDevice, applicationId: String): InstallOptions {
        val studioDevice = toStudioDevice(device)
        val options = InstallOptions.builder().setAllowDebuggable()
        if (studioDevice.supportsFeature(com.android.ddmlib.IDevice.HardwareFeature.EMBEDDED)) {
            options.setGrantAllPermissions()
        }
        if (device.version.isGreaterOrEqualThan(28)) {
            options.setInstallFullApk()
        }
        if (device.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.N)) {
            options.setDontKill()
        }
        options.setSkipVerification(studioDevice, applicationId)
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
        val studioLogger = toStudioLogger(logger)
        val apkInstaller = ApkInstaller(
            createAdbClient(toStudioDevice(device), studioLogger),
            session.toQuailUiService(),
            session.rawInstaller as Installer,
            studioLogger,
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
    ): Map<Int, JuggClassRedefiner> {
        return emptyMap()
    }

    override fun optimisticSwap(
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
    ): JuggOverlayId {
        val swapper = OptimisticApkSwapper(
            session.rawInstaller as Installer,
            redefiners.mapValues { it.value.raw as ClassRedefiner },
            argRestart,
            deployOptions,
            metrics,
        )
        val swapResult = swapper.optimisticSwap(packageName, pids, toStudioArch(arch), overlayUpdate.raw as OptimisticApkSwapper.OverlayUpdate)
        return swapResult.overlayId.toJuggOverlayId()
    }

    override fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState {
        if (device == null) {
            return IdeDeployState.deviceNotConnected
        }
        if (packageName == null) {
            return IdeDeployState.canNotDetectApplicationId
        }
        val studioDevice = toStudioDevice(device)
        return if (studioDevice.state == com.android.ddmlib.IDevice.DeviceState.UNAUTHORIZED) {
            IdeDeployState.deviceNotAuthorized
        } else if (!device.version.isGreaterOrEqualThan(IAsDeployerCompat.MIN_DEVICE_API)) {
            IdeDeployState.incompatibleDeviceApiLevel
        } else if (findClient(studioDevice, packageName).isEmpty()) {
            IdeDeployState.appNotRunningOrNotDebuggable
        } else {
            IdeDeployState.ok
        }
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return com.android.tools.deployer.model.ApkParser.parsePaths(paths).map(::toJuggApk)
    }

    override fun getPackageName(apks: List<Apk>): String {
        return ApplicationDumper.getPackageName(apks.map(::toStudioApk))
    }

    override fun createBaseOverlayId(apks: List<Apk>): JuggOverlayId {
        return OverlayId(apks.map(::toStudioApk)).toJuggOverlayId()
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
            toStudioChangedClasses(dexOverlays),
            fileOverlays.mapKeys { toStudioApkEntry(it.key) }.mapValues { toStudioByteString(it.value) },
        )
        return JuggOverlayUpdate(cachedDump, dexOverlays, fileOverlays, raw)
    }

    override fun dumpApks(session: JuggInstallSession, apks: List<Apk>): List<Apk> {
        return ApplicationDumper(session.rawInstaller as Installer).dump(apks.map(::toStudioApk)).apks
            .map(::toJuggApk)
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
        database.store(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME, apks.map(::toStudioApk), overlayId.raw as OverlayId)
        val entry = database.get(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME)
        return JuggDeploymentCacheEntry(entry, apks, overlayId)
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeployableToDevice.KEY, true)
    }

    override fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
        val client = AndroidDebugClientReadyWaiter().waitForWaitingDebuggerClient(toStudioDevice(device), packageName)
        AndroidStudioDebuggerAttachStarter().attachExistingProcess(project, client)
    }

    protected fun toJuggDevice(device: com.android.ddmlib.IDevice): IDevice = deployApiConverter.toJuggDevice(device)

    protected fun toStudioDevice(device: IDevice): com.android.ddmlib.IDevice = deployApiConverter.toStudioDevice(device)

    protected fun toStudioLogger(logger: ILogger): com.android.utils.ILogger = deployApiConverter.toStudioLogger(logger)

    protected fun toJuggApk(apk: com.android.tools.deployer.model.Apk): Apk = deployApiConverter.toJuggApk(apk)

    protected fun toStudioApk(apk: Apk): com.android.tools.deployer.model.Apk = deployApiConverter.toStudioApk(apk)

    protected fun toStudioApkEntry(entry: ApkEntry): com.android.tools.deployer.model.ApkEntry = deployApiConverter.toStudioApkEntry(entry)

    protected fun toStudioByteString(content: ByteString): com.android.tools.idea.protobuf.ByteString {
        return deployApiConverter.toStudioByteString(content)
    }

    protected fun toStudioChangedClasses(changes: DexComparator.ChangedClasses): com.android.tools.deployer.DexComparator.ChangedClasses {
        return deployApiConverter.toStudioChangedClasses(changes)
    }

    protected fun toStudioArch(arch: Deploy.Arch): com.android.tools.deploy.proto.Deploy.Arch {
        return deployApiConverter.toStudioArch(arch)
    }

    override fun getSuggestRunConfigurations(
        existsRunConfigNames: List<String>,
        project: Project,
        logger: Logger,
        isNeedDefaultRunConfig: Boolean,
    ): List<SuggestRunConfiguration> {
        val result = mutableListOf<SuggestRunConfiguration>()
        val androidConfigSettings = RunManager.getInstance(project).allSettings
            .filter { it.type is AndroidRunConfigurationType }
        androidConfigSettings.forEach { configSettings ->
            val suggestRunConfig = getSuggestRunConfiguration(configSettings, project, logger) ?: return@forEach
            result.add(suggestRunConfig)
        }

        if (result.isEmpty() && existsRunConfigNames.isEmpty() && isNeedDefaultRunConfig) {
            return listOf(SuggestRunConfiguration.DEFAULT)
        }
        return result.distinctBy { it.compileCommand to it.outputApkPath }
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
            buildDir = gradleAndroidModel.androidProject.buildFolder,
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
            val moduleName = SuggestRunConfiguration.resolveModuleName(module, project)
            val taskName = gradleAndroidModel.mainArtifact.assembleTaskName ?: return null
            val compileCommand = SuggestRunConfiguration.createCompileCommand(moduleName, taskName)
            val projectPath = project.basePath ?: return null
            val buildType = gradleAndroidModel.selectedVariant.buildType
            val productFlavorPath = gradleAndroidModel.selectedVariant.productFlavors
                .joinToString("") { it.replaceFirstChar { char -> char.uppercaseChar() } }
                .replaceFirstChar { it.lowercaseChar() }
                .takeIf { it.isNotEmpty() }
                ?.let { "$it/" }
                ?: ""
            val apkPath = SuggestRunConfiguration.createOutputApkPath(
                File(projectPath),
                gradleAndroidModel.androidProject.buildFolder,
                productFlavorPath,
                buildType,
            )
            SuggestRunConfiguration(
                moduleName = moduleName,
                compileCommand = compileCommand,
                outputApkPath = apkPath,
                variantName = gradleAndroidModel.selectedVariant.name,
            )
        } catch (e: Exception) {
            logger.debug("getSuggestRunConfiguration for ${settings.name} error, ignore", e)
            null
        }
    }

    private fun readAndroidTestPackageInfo(gradleAndroidModel: Any?): IdeAndroidTestPackageInfo {
        return IdeAndroidTestPackageReader.read(gradleAndroidModel)
    }

    private fun findClient(device: com.android.ddmlib.IDevice, packageName: String): List<Client> {
        val clazz = Class.forName("com.android.tools.idea.run.DeploymentApplicationService")
        val instance = clazz.getMethod("getInstance").invoke(null)
        val method = clazz.getMethod("findClient", com.android.ddmlib.IDevice::class.java, String::class.java)
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

    private fun createAdbClient(device: com.android.ddmlib.IDevice, logger: com.android.utils.ILogger): AdbClient {
        return AdbClient(device, logger, AdbLibApplicationService.instance.session)
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
