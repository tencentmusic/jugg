package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.Apk
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.run.*
import com.android.tools.idea.run.deployable.Deployable
import com.android.tools.idea.run.deployable.DeployableProvider
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet
import java.util.*
import java.util.concurrent.ExecutionException

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

    override fun getIdeDeployStateResult(project: Project): IdeDeployState {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return IdeDeployState.noAndroidConfiguration

        val deployableProvider = DeployableProvider.getInstance(project)
            ?: return IdeDeployState.noDeploymentProvider
        val deployable: Deployable?
        try {
            deployable = deployableProvider.getDeployable(selectedRunConfig)
            if (deployable == null) {
                return IdeDeployState.selectDeviceIsInvalid
            }
            if (!deployable.isOnline) {
                if (deployable.isUnauthorized) {
                    return IdeDeployState.deviceNotAuthorized
                } else {
                    return IdeDeployState.deviceNotConnected
                }
            }
            val versionFuture = deployable.version
            if (!versionFuture.isDone) {
                // Don't stall the EDT - if the Future isn't ready, just return false.
                return IdeDeployState.unknownDeviceApiLevel
            }
            if (versionFuture.get().apiLevel < IAsDeployerCompat.MIN_DEVICE_API) {
                return IdeDeployState.incompatibleDeviceApiLevel
            }
            if (deployable.searchClientsForPackage().isEmpty()) {
                return IdeDeployState.appNotRunningOrNotDebuggable
            }
        } catch (ex: InterruptedException) {
            return IdeDeployState.updateInterrupted
        } catch (ex: ExecutionException) {
            return IdeDeployState.unknownDeviceApiLevel
        } catch (ex: Exception) {
            return IdeDeployState.unknownDeviceApiLevel
        }
        return IdeDeployState.ok
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
