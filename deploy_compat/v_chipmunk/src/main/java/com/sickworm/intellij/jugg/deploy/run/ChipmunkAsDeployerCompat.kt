package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.Apk
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.run.*
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet
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

    override fun isSupportsSyncCallback(): Boolean {
        return false
    }

    override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider {
        return project.getProjectSystem().getApkProvider(config)!!
    }

    override fun getDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)

        // find the first available devices
        // TODO more elegant
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

        // TODO
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

    override fun getIdeDeployStateResult(project: Project, device: IDevice): IdeDeployState {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            if (it !is AndroidRunConfigurationBase) {
                return@firstOrNull false
            }
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return IdeDeployState.noAndroidConfiguration

        val androidRunConfiguration = selectedRunConfig as AndroidRunConfigurationBase
        val packageName = androidRunConfiguration.applicationIdProvider?.packageName
            ?: return IdeDeployState.noAndroidConfiguration

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
}
