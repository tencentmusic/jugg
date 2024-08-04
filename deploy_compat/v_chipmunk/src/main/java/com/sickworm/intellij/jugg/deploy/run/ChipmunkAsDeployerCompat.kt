package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.Apk
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.IdeAndroidArtifact
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.IdeSigningConfig
import com.android.tools.idea.gradle.model.IdeVariant
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.run.*
import com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxAction
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet
import java.io.File
import java.util.*

/**
 * Android Studio Chipmunk
 */
open class ChipmunkAsDeployerCompat: IAsDeployerCompat {

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

    override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider {
        return project.getProjectSystem().getApkProvider(config)!!
    }

    override fun getDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)

        // find the first available devices
        getModuleManager(project).modules.forEach { module ->
            val facet = AndroidFacet.getInstance(module) ?: return@forEach
            val deviceFutures = deployTarget.getDevices(facet) ?: return@forEach

            val devices = deviceFutures.ifReady
            if (!devices.isNullOrEmpty()) {
                return devices
            }
        }

        return null
    }

    override fun getInstaller(
        installersFolder: String,
        adb: AdbClient,
        logger: ILogger,
    ): AdbInstaller {
        var adbInstallerMode = AdbInstaller.Mode.DAEMON
        if (!StudioFlags.APPLY_CHANGES_KEEP_CONNECTION_ALIVE.get()) {
            adbInstallerMode = AdbInstaller.Mode.ONE_SHOT
        }
        return AdbInstaller(installersFolder, adb, metrics.deployMetrics, logger, adbInstallerMode)
    }

    override fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: InstallMode,
    ): Boolean {
        val apkInstaller = ApkInstaller(adb, service, installer, logger)
        return apkInstaller.install(packageName, apks, options, installMode, metrics.deployMetrics)
    }

    private fun getFastRerunOnSwapFailure(): Boolean {
        return myRerunOnSwapFailure && StudioFlags.APPLY_CHANGES_FAST_RESTART_ON_SWAP_FAIL.get()
    }

    override fun makeDebuggerRedefiners(
        project: Project,
        device: IDevice,
        fallback: Boolean
    ): ImmutableMap<Int, ClassRedefiner> {
        if (!DebuggerRedefiner.hasDebuggersAttached(project)) {
            return ImmutableMap.of()
        }
        val debugRedefiners = ImmutableMap.builder<Int, ClassRedefiner>()
        for (client in device.clients) {
            if (client.isDebuggerAttached) {
                val port = client.debuggerListenPort
                if (DebuggerRedefiner.getDebuggerSession(project, port) != null) {
                    val debugRedefiner: ClassRedefiner = DebuggerRedefiner(project, port, fallback)
                    debugRedefiners.put(client.clientData.pid, debugRedefiner)
                }
            }
        }
        return debugRedefiners.build()
    }

    override fun optimisticSwap(
        installer: Installer,
        redefiners: Map<Int, ClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: OverlayUpdate,
        adb: AdbClient,
        logger: ILogger
    ): OverlayId {
        val swapper = OptimisticApkSwapper(
            installer,
            redefiners,
            argRestart,
            options,
            metrics
        )
        val swapResult = swapper.optimisticSwap(packageName, pids, arch, overlayUpdate)

        //  java.lang.IllegalAccessError: class com.sickworm.intellij.jugg.deploy.run.JuggDeployer tried to access method
        //  'void com.android.tools.deployer.MetricsRecorder.add(com.android.tools.deployer.DeployMetric)'
        //  (com.sickworm.intellij.jugg.deploy.run.JuggDeployer is in unnamed module of loader
        //  com.intellij.ide.plugins.cl.PluginClassLoader @505163f; com.android.tools.deployer.MetricsRecorder
        //  is in unnamed module of loader
//        result.getMetrics().forEach(metrics::add);

        return swapResult.overlayId
    }

    override fun toApkProvider(apkInfos: List<ApkInfo>): ApkProvider {
        return object : ApkProvider {
            override fun getApks(device: IDevice): MutableCollection<ApkInfo> {
                return apkInfos.toMutableList()
            }

            override fun validate(): MutableList<ValidationError> {
                return mutableListOf()
            }
        }
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
        } else if (findClientCompat(device, packageName).isEmpty()) {
            IdeDeployState.appNotRunningOrNotDebuggable
        } else {
            IdeDeployState.ok
        }
    }

    private fun findClientCompat(device: IDevice, packageName: String): List<Client> {
        return try {
            DeploymentApplicationService.getInstance().findClient(device, packageName)
        } catch (e: IncompatibleClassChangeError) {
            val clazz = Class.forName("com.android.tools.idea.run.DeploymentApplicationService")
            val method = clazz.getDeclaredMethod("getInstance")
            val instance = method.invoke(null)
            val findClientMethod = clazz.getDeclaredMethod("findClient", IDevice::class.java, String::class.java)
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

    override fun getDeploymentService(project: Project): DeploymentService {
        return DeploymentService.getInstance(project)
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return ApkParser().parsePaths(paths)
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeviceAndSnapshotComboBoxAction.DEPLOYS_TO_LOCAL_DEVICE, true)
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

        // returns empty with new created project, have to use allConfigurationsList and filter by myself
//        var androidConfigSettings = RunManager.getInstance(project)
//            .getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
//        logger.debug("androidConfigSettings ${androidConfigSettings.map { it.name }}")

        val allConfigSettings = RunManager.getInstance(project).allSettings
        logger.debug("allConfigSettings ${allConfigSettings.map { "${it.name}(${it.type})" }}")
        val androidConfigSettings = allConfigSettings.filter { it.type is AndroidRunConfigurationType }
        logger.debug("androidConfigSettings ${androidConfigSettings.map { it.name }}")


        // compatible with old jugg config. if project has old config "jugg:app" and the module have none app module,
        // ignore suggest to avoid duplicate configs
        val hasOldConfigs = androidConfigSettings.all { it.name != "app" }
                && existsModuleForRunConfig.any { it == "app" }
        if (hasOldConfigs) {
            logger.debug("getSuggestRunConfigurations: detect old jugg config, ignore suggest")
            return emptyList()
        }

        androidConfigSettings.forEach { configSettings ->
            val suggestRunConfig = getSuggestRunConfiguration(configSettings, project, logger)
            if (suggestRunConfig == null) {
                logger.debug("getSuggestRunConfigurations: runConfig ${configSettings.name} suggestRunConfig " +
                        "is null, ignore")
                return@forEach
            }
            if (suggestRunConfig.moduleName in existsModuleForRunConfig) {
                logger.debug("getSuggestRunConfigurations: runConfig ${configSettings.name} already has relative " +
                        "Jugg config ${suggestRunConfig.runConfigName}, ignore")
                return@forEach
            }
            logger.debug("getSuggestRunConfigurations: add suggest runConfig $suggestRunConfig")
            result.add(suggestRunConfig)
        }

        if (result.isEmpty() && existsRunConfigNames.isEmpty() && isNeedDefaultRunConfig) {
            logger.debug("getSuggestRunConfigurations: no suggest run config and no exists run config, use default")
            return listOf(SuggestRunConfiguration.DEFAULT)
        }

        return result
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
                logger.warn("print gradleAndroidModel failed", e)
            }
            gradleAndroidModel ?: return null

            // get compile command
            val moduleName = gradleAndroidModel.moduleName.split('.').last()
            val taskName = gradleAndroidModel.mainArtifact.assembleTaskName
            val compileCommand = "./gradlew :$moduleName:$taskName"

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
            val moduleRelativePath = gradleAndroidModel.rootDirPath.relativeTo(File(projectPath)).path
            val apkPath = moduleRelativePath.replace("\\", "/") + "/build/outputs/apk/$productFlavorPath$buildType/*.apk"
            logger.debug("getSuggestRunConfiguration use apk output path: $apkPath")

            return SuggestRunConfiguration(moduleName, compileCommand, apkPath)
        } catch (e: Throwable) {
            logger.debug("getSuggestRunConfiguration for ${settings.name} error, ignore", e)
            return null
        }
    }

    private fun GradleAndroidModel.getDesc(): String {
        return "GradleAndroidModel: " +
                "moduleName: ${moduleName}, " +
                "rootDirPath: ${rootDirPath}, " +
                "variantNames: ${variantNames}, " +
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
}
