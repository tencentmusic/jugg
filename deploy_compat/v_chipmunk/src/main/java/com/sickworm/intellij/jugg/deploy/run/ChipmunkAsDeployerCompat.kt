package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.android.facet.AndroidFacet
import java.util.*

/**
 * Android Studio Chipmunk
 */
class ChipmunkAsDeployerCompat: IAsDeployerCompat {

    companion object {

        val deployVersion: ComparableVersion = ComparableVersion("27.2.0.0")

    }

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
        val module = ModuleManager.getInstance(project).modules.first()
        val facet = AndroidFacet.getInstance(module)
            ?: throw IllegalStateException("no android facet")

        val deviceFutures = deployTarget.getDevices(facet)
            ?: throw IllegalStateException("no device futures")

        // got ClassCastException if using DeviceFutures.get() for different ClassLoader
        return deviceFutures.ifReady
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
}
