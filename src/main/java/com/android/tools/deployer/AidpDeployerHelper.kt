package com.android.tools.deployer

import com.android.ddmlib.IDevice
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.util.EmbeddedDistributionPaths
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.*
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.tools.idea.run.tasks.AidpAbstractDeployTask
import com.google.common.base.Stopwatch
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.aidp.AidpLogger
import org.jetbrains.android.facet.AndroidFacet
import java.util.*

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
object AidpDeployerHelper {

    var installPathProvider: Computable<String> = Computable<String> {
        EmbeddedDistributionPaths.getInstance().findEmbeddedInstaller()
    }

    fun runTask(project: Project) {
        val device = getIDevice(project)
        val stopwatch = Stopwatch.createStarted()
        val logger = LogWrapper(AidpLogger.getInstance(project, "#AIDP-AidpDeployer"))

        // Collection that will accumulate metrics for the deployment.
        val metrics = ArrayList<DeployMetric>()
        // VM clock timestamp used to snap metric times to wall-clock time.
        val vmClockStartNs = System.nanoTime()
        // Wall-clock start time for the deployment.
        val wallClockStartMs = System.currentTimeMillis()

        val adb = AdbClient(device, logger)
        val installer: Installer = AdbInstaller(getLocalInstaller(), adb, metrics, logger)
        val service = DeploymentService.getInstance(project)
        val ideService = IdeService(project)
        val deployer = AidpDeployer(
            adb, service.deploymentCacheDatabase, service.dexDatabase, service.taskRunner,
            installer, ideService, metrics, logger, StudioFlags.APPLY_CHANGES_OPTIMISTIC_SWAP.get(),
            StudioFlags.APPLY_CHANGES_OPTIMISTIC_RESOURCE_SWAP.get(),
            StudioFlags.APPLY_CHANGES_STRUCTURAL_DEFINITION.get(),
            StudioFlags.APPLY_CHANGES_VARIABLE_REINITIALIZATION.get()
        )
        deployer.codeSwap(emptyList(), emptyMap())
    }

    private fun getLocalInstaller(): String? {
        return installPathProvider.compute()
    }

    private fun getIDevice(project: Project): IDevice {
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
        val deployTargetState: DeployTargetState = deployTargetContext.currentDeployTargetState
//        val module = JavaRunConfigurationModule(project, false)
//        module.setModuleName("app") // TODO read from project
        val module = ModuleManager.getInstance(project).modules.first { it.name.contains("app") }
        val facet = AndroidFacet.getInstance(module!!)!!

        val deviceFutures =
            deployTarget.getDevices(deployTargetState, facet, getDeviceCount(isDebugging), isDebugging, hashCode())
                ?: throw IllegalStateException("no device futures")

        // got ClassCastException if use DeviceFutures.get() for different ClassLoader
        val devices = deviceFutures.ifReady
        if (devices.isNullOrEmpty()) {
            throw IllegalStateException("no devices")
        }

        return devices[0]
    }

    fun getConfiguration(project: Project): AndroidRunConfiguration {
        val factory = AndroidRunConfigurationType.getInstance().factory
        return factory.createTemplateConfiguration(project) as AndroidRunConfiguration
    }

//    private fun waitForDevice(
//        deviceFuture: ListenableFuture<IDevice>,
//        indicator: ProgressIndicator,
//        launchStatus: LaunchStatus,
//        destroyProcess: Boolean
//    ): IDevice? {
//        var device: IDevice? = null
//        while (checkIfLaunchIsAliveAndTerminateIfCancelIsRequested(
//                indicator, launchStatus, destroyProcess)) {
//            try {
//                device = deviceFuture[1, TimeUnit.SECONDS]
//                break
//            } catch (ignored: TimeoutException) {
//                // Let's check the cancellation request then continue to wait for a device again.
//            } catch (e: InterruptedException) {
//                launchStatus.terminateLaunch("Interrupted while waiting for device", destroyProcess)
//                break
//            } catch (e: ExecutionException) {
//                launchStatus.terminateLaunch("Error while waiting for device: " + e.cause!!.message, destroyProcess)
//                break
//            }
//        }
//        return device
//    }
//
//    private fun checkIfLaunchIsAliveAndTerminateIfCancelIsRequested(
//        indicator: ProgressIndicator, launchStatus: LaunchStatus, destroyProcess: Boolean
//    ): Boolean {
//        // Check for cancellation via stop button or unexpected failures in launch tasks.
//        if (launchStatus.isLaunchTerminated) {
//            return false
//        }
//
//        // Check for cancellation via progress bar.
//        if (indicator.isCanceled) {
//            launchStatus.terminateLaunch("User cancelled launch", destroyProcess)
//            return false
//        }
//        return true
//    }

    private fun getDeviceCount(debug: Boolean): DeviceCount {
        return DeviceCount.fromBoolean(supportMultipleDevices() && !debug)
    }

    private val isDebugging = true // ??
    private val deployTargetContext = DeployTargetContext()
    private fun supportMultipleDevices() = false
}