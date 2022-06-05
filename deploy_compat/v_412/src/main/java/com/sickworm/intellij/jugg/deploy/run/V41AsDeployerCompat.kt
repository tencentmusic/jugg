package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeviceCount
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.tools.idea.run.util.DebuggerRedefiner
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet
import kotlin.jvm.Throws

/**
 * Android Studio 4.1
 */
class V41AsDeployerCompat : IAsDeployerCompat {

    // Collection that will accumulate metrics for the deployment.
    private val metrics = ArrayList<DeployMetric>()
    private val useStructuralRedefinition = StudioFlags.APPLY_CHANGES_STRUCTURAL_DEFINITION.get()
    private val useVariableReinitialization = StudioFlags.APPLY_CHANGES_VARIABLE_REINITIALIZATION.get()

    override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider {
        val targetDeviceSpec = null
        val module: Module = config.configurationModule.module!!
        val facet = AndroidFacet.getInstance(module)!!
        return facet.getModuleSystem().getApkProvider(config, targetDeviceSpec)!!
    }

    override fun getDevices(project: Project): List<IDevice>? {
        val isDebugging = true
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
        val deployTargetState: DeployTargetState = deployTargetContext.currentDeployTargetState
        val module = ModuleManager.getInstance(project).modules.first()
        val facet = AndroidFacet.getInstance(module)
            ?: throw IllegalStateException("no android facet")

        val deviceFutures =
            deployTarget.getDevices(deployTargetState, facet, getDeviceCount(isDebugging), isDebugging, hashCode())
                ?: throw IllegalStateException("no device futures")

        // got ClassCastException if using DeviceFutures.get() for different ClassLoader
        return deviceFutures.ifReady
    }

    private fun getDeviceCount(@Suppress("SameParameterValue") debug: Boolean): DeviceCount {
        val supportMultipleDevices = false
        return DeviceCount.fromBoolean(supportMultipleDevices && !debug)
    }

    override fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller {
        return AdbInstaller(installersFolder, adb, metrics, logger)
    }

    override fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: Deployer.InstallMode
    ): Boolean {
        val apkInstaller = ApkInstaller(adb, service, installer, logger)
        return apkInstaller.install(packageName, apks, options, installMode, metrics)
    }

    override fun makeDebuggerRedefiners(
        project: Project,
        device: IDevice,
        fallback: Boolean
    ): Map<Int, ClassRedefiner> {
        if (!DebuggerRedefiner.hasDebuggersAttached(project)) {
            return ImmutableMap.of()
        }

        val debugRedefiners = ImmutableMap.builder<Int, ClassRedefiner>()
        for (client in device.clients) {
            if (client.isDebuggerAttached) {
                val port = client.debuggerListenPort
                if (DebuggerRedefiner.getDebuggerSession(project, port) != null) {
                    val debugRedefiner: ClassRedefiner = DebuggerRedefiner(project, port)
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
        overlayUpdate: OptimisticApkSwapper.OverlayUpdate,
        adb: AdbClient,
        logger: ILogger,
    ): OverlayId {
        val swapper = OptimisticApkSwapper(
            installer,
            redefiners,
            argRestart,
            useStructuralRedefinition,
            useVariableReinitialization,
            adb,
            logger
        )
        return swapper.optimisticSwap(packageName, pids, arch, overlayUpdate)
    }
}
