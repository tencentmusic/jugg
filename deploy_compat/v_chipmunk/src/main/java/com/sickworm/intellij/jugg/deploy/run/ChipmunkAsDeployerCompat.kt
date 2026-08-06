package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.android.tools.idea.adb.AdbService
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.java.LanguageLevelPropertyModel
import com.android.tools.idea.gradle.model.IdeAndroidArtifact
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.IdeSigningConfig
import com.android.tools.idea.gradle.model.IdeVariant
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.run.*
import com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxAction
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.io.File
import java.util.*

/**
 * Android Studio Chipmunk
 */
open class ChipmunkAsDeployerCompat: IAsDeployerCompat {

    private val deployApiConverter = LegacyDeployApiConverter()

    private val optimisticInstallSupportFull: Map<StudioFlags.OptimisticInstallSupportLevel, EnumSet<ChangeType>>
            = ImmutableMap.of(
        StudioFlags.OptimisticInstallSupportLevel.DISABLED, EnumSet.noneOf(ChangeType::class.java),
        StudioFlags.OptimisticInstallSupportLevel.DEX, EnumSet.of(ChangeType.DEX),
        StudioFlags.OptimisticInstallSupportLevel.DEX_AND_NATIVE,
        EnumSet.of(ChangeType.DEX, ChangeType.NATIVE_LIBRARY),
        StudioFlags.OptimisticInstallSupportLevel.DEX_AND_NATIVE_AND_RESOURCES,
        EnumSet.of(
            ChangeType.DEX,
            ChangeType.NATIVE_LIBRARY,
            ChangeType.RESOURCE)
    )

    private val myRerunOnSwapFailure: Boolean = false
    private val myAlwaysInstallWithPm: Boolean = false
    private val optimisticInstallSupport: EnumSet<ChangeType> =
        if (!myAlwaysInstallWithPm) {
            optimisticInstallSupportFull.getOrDefault(
                StudioFlags.OPTIMISTIC_INSTALL_SUPPORT_LEVEL.get(), EnumSet.noneOf(ChangeType::class.java)
            )
        } else {
            EnumSet.noneOf(ChangeType::class.java)
        }

    private val options = DeployerOption.Builder()
        .setUseOptimisticSwap(StudioFlags.APPLY_CHANGES_OPTIMISTIC_SWAP.get())
        .setUseOptimisticResourceSwap(StudioFlags.APPLY_CHANGES_OPTIMISTIC_RESOURCE_SWAP.get())
        .setOptimisticInstallSupport(optimisticInstallSupport)
        .setUseStructuralRedefinition(StudioFlags.APPLY_CHANGES_STRUCTURAL_DEFINITION.get())
        .setUseVariableReinitialization(StudioFlags.APPLY_CHANGES_VARIABLE_REINITIALIZATION.get())
        .setFastRestartOnSwapFail(getFastRerunOnSwapFailure())
        .build()

    // Collection that will accumulate metrics for the deployment.
    val metrics = MetricsRecorder()

    /**
     * @see com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxTarget.getDevices (will boot avd)
     */
    override fun getSelectedDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)

        // find the first available devices
        getModuleManager(project).modules.forEach { module ->
            val facet = AndroidFacet.getInstance(module) ?: return@forEach
            val deviceFutures = deployTarget.getDevices(facet) ?: return@forEach

            val devices = deviceFutures.ifReady
            if (!devices.isNullOrEmpty()) {
                return devices.map(::toJuggDevice)
            }
        }

        return null
    }

    /**
     * @see com.android.tools.idea.run.deployment.DdmlibAndroidDebugBridge.getDevices
     */
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
        var adbInstallerMode = AdbInstaller.Mode.DAEMON
        if (!StudioFlags.APPLY_CHANGES_KEEP_CONNECTION_ALIVE.get()) {
            adbInstallerMode = AdbInstaller.Mode.ONE_SHOT
        }
        val studioLogger = toStudioLogger(logger)
        val adb = createLegacyAdbClient(toStudioDevice(device), studioLogger)
        val installer = AdbInstaller(installersFolder, adb, metrics.deployMetrics, studioLogger, adbInstallerMode)
        return JuggInstallSession(this, installer, installer.version, onPrompt, onMessage)
    }

    protected fun createInstallOptions(device: IDevice, applicationId: String): InstallOptions {
        val studioDevice = toStudioDevice(device)
        val options = InstallOptions.builder().setAllowDebuggable()
        if (studioDevice.supportsFeature(com.android.ddmlib.IDevice.HardwareFeature.EMBEDDED)) {
            options.setGrantAllPermissions()
        }
        if (device.version.isGreaterOrEqualThan(28)) {
            options.setInstallFullApk()
        }
        if (device.version.isGreaterOrEqualThan(com.android.sdklib.AndroidVersion.VersionCodes.N)) {
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
        val adb = createLegacyAdbClient(toStudioDevice(device), studioLogger)
        val apkInstaller = ApkInstaller(adb, session.toLegacyUiService(), session.rawInstaller as Installer, studioLogger)
        return apkInstaller.install(
            packageName,
            apks,
            createInstallOptions(device, packageName),
            installMode.toLegacyInstallMode(),
            metrics.deployMetrics,
        )
    }

    override fun getInstallMode(): JuggInstallSession.Mode {
        return if (StudioFlags.DELTA_INSTALL.get()) {
            JuggInstallSession.Mode.DELTA
        } else {
            JuggInstallSession.Mode.FULL
        }
    }

    private fun getFastRerunOnSwapFailure(): Boolean {
        return myRerunOnSwapFailure && StudioFlags.APPLY_CHANGES_FAST_RESTART_ON_SWAP_FAIL.get()
    }

    override fun makeDebuggerRedefiners(
        project: Project,
        device: IDevice,
        fallback: Boolean
    ): Map<Int, JuggClassRedefiner> {
        if (!DebuggerRedefiner.hasDebuggersAttached(project)) {
            return emptyMap()
        }
        val debugRedefiners = mutableMapOf<Int, JuggClassRedefiner>()
        for (client in toStudioDevice(device).clients) {
            if (client.isDebuggerAttached) {
                val port = client.debuggerListenPort
                if (DebuggerRedefiner.getDebuggerSession(project, port) != null) {
                    val debugRedefiner: ClassRedefiner = DebuggerRedefiner(project, port, fallback)
                    debugRedefiners[client.clientData.pid] = JuggClassRedefiner(debugRedefiner)
                }
            }
        }
        return debugRedefiners
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
        val rawInstaller = session.rawInstaller as Installer
        val rawRedefiners = redefiners.mapValues { it.value.raw as ClassRedefiner }
        if (isPushOverlayOnly) {
            val updater = OptimisticApkUpdater(rawInstaller, rawRedefiners)
            val swapResult = updater.pushOverlays(
                packageName,
                pids,
                toStudioArch(arch),
                overlayUpdate.cachedDump,
                toStudioChangedClasses(overlayUpdate.dexOverlays),
                overlayUpdate.fileOverlays.mapKeys { toStudioApkEntry(it.key) }
                    .mapValues { toStudioByteString(it.value) },
            )
            return swapResult.overlayId.toJuggOverlayId()
        }

        val swapper = OptimisticApkSwapper(
            rawInstaller,
            rawRedefiners,
            argRestart,
            options,
            metrics
        )
        val swapResult = swapper.optimisticSwap(packageName, pids, toStudioArch(arch), overlayUpdate.raw as OptimisticApkSwapper.OverlayUpdate)

        //  java.lang.IllegalAccessError: class com.sickworm.intellij.jugg.deploy.run.JuggDeployer tried to access method
        //  'void com.android.tools.deployer.MetricsRecorder.add(com.android.tools.deployer.DeployMetric)'
        //  (com.sickworm.intellij.jugg.deploy.run.JuggDeployer is in unnamed module of loader
        //  com.intellij.ide.plugins.cl.PluginClassLoader @505163f; com.android.tools.deployer.MetricsRecorder
        //  is in unnamed module of loader
//        result.getMetrics().forEach(metrics::add);

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
        } else if (findClientCompat(studioDevice, packageName).isEmpty()) {
            IdeDeployState.appNotRunningOrNotDebuggable
        } else {
            IdeDeployState.ok
        }
    }

    protected fun findClientCompat(device: com.android.ddmlib.IDevice, packageName: String): List<Client> {
        return try {
            DeploymentApplicationService.getInstance().findClient(device, packageName)
        } catch (e: IncompatibleClassChangeError) {
            val clazz = Class.forName("com.android.tools.idea.run.DeploymentApplicationService")
            val method = clazz.getDeclaredMethod("getInstance")
            val instance = method.invoke(null)
            val findClientMethod = clazz.getDeclaredMethod("findClient", com.android.ddmlib.IDevice::class.java, String::class.java)
            @Suppress("UNCHECKED_CAST")
            findClientMethod.invoke(instance, device, packageName) as List<Client>
        }
    }

    private fun isApplyChangesRelevant(runConfiguration: RunConfiguration): Boolean {
        if (runConfiguration is RunConfigurationBase<*>) {
            return runConfiguration.putUserDataIfAbsent(
                BaseAction.SHOW_APPLY_CHANGES_UI,
                false
            ) // This is needed to prevent a NPE if the boolean isn't set.
        }
        return false
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return ApkParser().parsePaths(paths).map(::toJuggApk)
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
            cachedDump.raw as DeploymentCacheDatabase.Entry,
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
        val database = DeploymentCacheDatabase(1)
        database.store(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME, apks.map(::toStudioApk), overlayId.raw as OverlayId)
        val entry = database.get(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME)
        return JuggDeploymentCacheEntry(entry, apks, overlayId)
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeviceAndSnapshotComboBoxAction.DEPLOYS_TO_LOCAL_DEVICE, true)
    }

    override fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
        throw UnsupportedOperationException("Jugg Debug is not supported in this Android Studio version.")
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

        // returns empty with new created project, have to use allConfigurationsList and filter by myself
//        var androidConfigSettings = RunManager.getInstance(project)
//            .getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
//        logger.debug("androidConfigSettings ${androidConfigSettings.map { it.name }}")

        val allConfigSettings = RunManager.getInstance(project).allSettings
        logger.debug("allConfigSettings ${allConfigSettings.map { "${it.name}(${it.type})" }}")
        val androidConfigSettings = allConfigSettings.filter { it.type is AndroidRunConfigurationType }
        logger.debug("androidConfigSettings ${androidConfigSettings.map { it.name }}")

        androidConfigSettings.forEach { configSettings ->
            val suggestRunConfig = getSuggestRunConfiguration(configSettings, project, logger)
            if (suggestRunConfig == null) {
                logger.debug("getSuggestRunConfigurations: runConfig ${configSettings.name} suggestRunConfig " +
                        "is null, ignore")
                return@forEach
            }
            logger.debug("getSuggestRunConfigurations: add suggest runConfig $suggestRunConfig")
            result.add(suggestRunConfig)
        }

        if (result.isEmpty() && existsRunConfigNames.isEmpty() && isNeedDefaultRunConfig) {
            logger.debug("getSuggestRunConfigurations: no suggest run config and no exists run config, use default")
            return listOf(SuggestRunConfiguration.DEFAULT)
        }

        return result.distinctBy { it.compileCommand to it.outputApkPath }
    }

    private fun getSuggestRunConfiguration(settings: RunnerAndConfigurationSettings,
                                           project: Project,
                                           logger: Logger,
    ): SuggestRunConfiguration? {
        try {
            // get build module
            val runConfig = settings.configuration as AndroidRunConfiguration
            val module = runConfig.modules.firstOrNull()
            if (module == null) {
                logger.debug("getSuggestRunConfiguration module of runConfig ${runConfig.name} is null")
                return null
            }
            val gradleAndroidModel = GradleAndroidModel.get(module)
            try {
                logger.debug("getSuggestRunConfiguration gradleAndroidModel: ${gradleAndroidModel?.getDesc()}")
            } catch (e: Throwable) {
                logger.debug("print gradleAndroidModel failed", e)
            }
            gradleAndroidModel ?: return null

            // get compile command
            val moduleName = SuggestRunConfiguration.resolveModuleName(module, project)
            val taskName = gradleAndroidModel.mainArtifact.assembleTaskName
            val compileCommand = SuggestRunConfiguration.createCompileCommand(moduleName, taskName)

            // get apk
            val projectPath = project.basePath!!
            val buildType = gradleAndroidModel.selectedVariant.buildType
            var productFlavorPath = ""
            if (gradleAndroidModel.selectedVariant.productFlavors.isNotEmpty()) {
                gradleAndroidModel.selectedVariant.productFlavors.forEach { flavor ->
                    if (productFlavorPath.isEmpty()) {
                        productFlavorPath = flavor
                    } else {
                        productFlavorPath += flavor.replaceFirstChar { it.uppercaseChar() }
                    }
                }
                productFlavorPath += "/"
            }
            val apkPath = SuggestRunConfiguration.createOutputApkPath(
                File(projectPath),
                gradleAndroidModel.androidProject.buildFolder,
                productFlavorPath,
                buildType,
            )
            logger.debug("getSuggestRunConfiguration use apk output path: $apkPath")

            return SuggestRunConfiguration(
                moduleName = moduleName,
                compileCommand = compileCommand,
                outputApkPath = apkPath,
                variantName = gradleAndroidModel.selectedVariant.name,
            )
        } catch (e: Exception) {
            logger.debug("getSuggestRunConfiguration for ${settings.name} error, ignore", e)
            return null
        }
    }

    private fun GradleAndroidModel.getDesc(): String {
        return "GradleAndroidModel: " +
                "moduleName: ${moduleName}, " +
                "rootDirPath: ${rootDirPath}, " +
                "variantNames: ${try { variantNames } catch (e: Throwable) { e::class.simpleName }}, " +
                "minSdkVersion: ${minSdkVersion}, " +
                "isDebuggable: ${isDebuggable}, " +
                "variant: ${selectedVariant.name}, " +
                "buildType: ${selectedVariant.buildType}, " +
                "productFlavors: ${selectedVariant.productFlavors}, " +
                "agpVersion: ${androidProject.agpVersion}, " +
                "allApplicationIds: ${allApplicationIds}, " +
                "isBaseSplit: ${isBaseSplit}, " +
                "mainArtifact: ${mainArtifact.getDesc()}, " +
                "androidProject: ${androidProject.getDesc()}, " +
                ""
    }

    private fun IdeAndroidArtifact.getDesc(): String {
        return "IdeAndroidArtifact: " +
                "assembleTaskName: $assembleTaskName, " +
                "unresolvedDependencies: $unresolvedDependencies, " +
                "signingConfigName: $signingConfigName, " +
                "isSigned: $isSigned, " +
                "buildInformation: $buildInformation" +
                ""
    }

    private fun IdeAndroidProject.getDesc(): String {
        return "IdeAndroidProject: " +
                "compileTarget: $compileTarget, " +
                "bootClasspath: $bootClasspath, " +
                "signingConfigs: ${signingConfigs.map { it.getDesc() }}, " +
                "javaCompileOptions: $javaCompileOptions, " +
                "viewBindingOptions: $viewBindingOptions, " +
                "namespace: $namespace, " +
                "agpFlags: $agpFlags, " +
                "variantsBuildInformation: ${variantsBuildInformation.map { it.variantName }}, " +
                ""
    }

    private fun IdeSigningConfig.getDesc(): String {
        return "IdeSigningConfig(name=$name, " +
                "storeFile=${if (storeFile == null) "null" else if (!storeFile!!.exists()) "not exists" else "exists"}, " +
                "storePassword=${if (storePassword == null) "null" else "not null"}, " +
                "keyAlias=${if (keyAlias == null) "null" else "not null"}"
    }

    private fun GradleAndroidModel.getVariantsCompat(): List<IdeVariant> {
        // ImmutableList<IdeVariant> getVariants() in Android Studio Chipmunk
        // java.util.List<IdeVariant> getVariants() in Intellij Idea
        // directly call will get NoSuchMethodError
        val method = GradleAndroidModel::class.java.getDeclaredMethod("getVariants")
        @Suppress("UNCHECKED_CAST")
        return method.invoke(this) as List<IdeVariant>
    }

    override fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo? {
        val projectBuildModel = ProjectBuildModel.get(project)
        val buildModel = projectBuildModel.getModuleBuildModel(module) ?: return null
        val gradleVariableHelper = GradleVariableHelper(isSafeMode)

        val androidFacet = AndroidFacet.getInstance(module)
        var buildVariant = androidFacet?.properties?.SELECTED_BUILD_VARIANT
        if (buildVariant.isNullOrEmpty()) {
            buildVariant = "debug"
        }
        val gradleAndroidModel = runCatching { GradleAndroidModel.get(module) }.getOrNull()
        val androidTestPackageInfo = IdeAndroidTestPackageReader.read(gradleAndroidModel)

        val result = IdeModuleInfo(
            baseDir = module.guessModuleDirAdv(projectBuildModel),
            buildToolsVersion = gradleVariableHelper.readVariable(
                "buildToolsVersion",
                buildModel,
                { buildModel.android().buildToolsVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            compileVersion = gradleVariableHelper.readVariable(
                "compileSdkVersion",
                buildModel,
                { buildModel.android().compileSdkVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            minSdkVersion = gradleVariableHelper.readVariable(
                "minSdkVersion",
                buildModel,
                { buildModel.android().defaultConfig().minSdkVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            kotlinJvmTarget = gradleVariableHelper.readVariable("kotlinJvmTarget") {
                buildModel.android().kotlinOptions().jvmTarget().toJavaVersion()
            },
            kotlinFreeCompilerArgs = gradleVariableHelper.readVariable("kotlinFreeCompilerArgs") {
                buildModel.android().kotlinOptions().freeCompilerArgs()
                    .toList()?.map { it.toString() } ?: emptyList()
            },
            javaSourceCompatibility = gradleVariableHelper.readVariable("javaSourceCompatibility") {
                buildModel.android().compileOptions().sourceCompatibility().toJavaVersion()
            },
            javaTargetCompatibility = gradleVariableHelper.readVariable("javaTargetCompatibility") {
                buildModel.android().compileOptions().targetCompatibility().toJavaVersion()
            },
            minifyEnabled = gradleVariableHelper.readVariable("minifyEnabled") {
                buildModel.android().buildTypes()
                    .find { it.name() == buildVariant }
                    ?.minifyEnabled()?.toString()
            },
            buildVariant = buildVariant,
            manifestRelativePath = gradleVariableHelper.readVariable("manifestRelativePath") {
                androidFacet?.properties?.MANIFEST_FILE_RELATIVE_PATH
            },
            brokenFields = gradleVariableHelper.brokenFields,
            androidTestApplicationId = androidTestPackageInfo.applicationId,
            androidTestInstrumentationTargetPackage = androidTestPackageInfo.instrumentationTargetPackage,
            buildDir = gradleAndroidModel?.androidProject?.buildFolder,
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

    fun LanguageLevelPropertyModel.toJavaVersion(): String? {
        val languageLevel = this::class.java.getMethod("toLanguageLevel").invoke(this) ?: return null
        val javaVersion = languageLevel::class.java.getMethod("toJavaVersion").invoke(languageLevel) ?: return null
        return javaVersion.toString()
    }

    fun Module.guessModuleDirAdv(projectBuildModel: ProjectBuildModel): File? {
        val gradleRootDir = projectBuildModel.getModuleBuildModel(this)?.moduleRootDirectory
        if (gradleRootDir != null) {
            return gradleRootDir
        }

        val contentRoots = rootManager.contentRoots.filter { it.isDirectory }
        val virtualFile = contentRoots.find { name.endsWith(it.name) }
            ?: contentRoots.firstOrNull()
            ?: moduleFile?.parent
            ?: return null
        return VfsUtil.virtualToIoFile(virtualFile)
    }

    protected fun createLegacyAdbClient(device: com.android.ddmlib.IDevice, logger: com.android.utils.ILogger): AdbClient {
        return AdbClient(device, logger)
    }

    protected fun OverlayId.toJuggOverlayId(): JuggOverlayId {
        val overlayFiles = overlayContents.allFiles().map {
            JuggOverlayFile(it, overlayContents.getFileChecksum(it))
        }
        return JuggOverlayId(this, sha, isBaseInstall, overlayFiles)
    }

    protected fun DeployerException.toJuggDeployerException(): JuggDeployerException {
        return JuggDeployerException(error.ordinal, message, details, this)
    }

    protected fun JuggInstallSession.toLegacyUiService(): UIService {
        val session = this
        return object : UIService {
            override fun prompt(message: String): Boolean = session.prompt(message)

            override fun message(message: String) = session.message(message)
        }
    }

    protected fun JuggInstallSession.Mode.toLegacyInstallMode(): InstallMode {
        return when (this) {
            JuggInstallSession.Mode.DELTA -> InstallMode.DELTA
            JuggInstallSession.Mode.DELTA_NO_SKIP -> InstallMode.DELTA_NO_SKIP
            JuggInstallSession.Mode.FULL -> InstallMode.FULL
        }
    }

    private companion object {
        const val MEMORY_DEVICE_SERIAL = "memory"
        const val MEMORY_PACKAGE_NAME = "entry"
    }
}
