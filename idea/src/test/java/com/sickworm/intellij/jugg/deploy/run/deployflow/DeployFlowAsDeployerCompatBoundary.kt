package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.Installer
import com.android.tools.deployer.OverlayId
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.InstallOptions
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.android.tools.deployer.UIService
import com.android.tools.deployer.ClassRedefiner
import com.android.utils.ILogger
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import org.mockito.Mockito

/**
 * Deploy-flow physical boundary for install and Apply Changes swap.
 * Other compat APIs delegate to production [AsDeployerCompat].
 */
class DeployFlowAsDeployerCompatBoundary(
    private val virtualDevice: VirtualDeployDevice,
    private val optimisticSwapPolicy: OptimisticSwapPolicy = OptimisticSwapPolicy.FORBIDDEN,
    private val onInstall: Runnable = Runnable { virtualDevice.onInstallCompleted() },
    private val installerVersion: String? = null,
    private val delegate: IAsDeployerCompat = AsDeployerCompat,
) : IAsDeployerCompat by delegate {

    var optimisticSwapInvokeCount: Int = 0
        private set

    override fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller {
        val installer = Mockito.mock(AdbInstaller::class.java)
        if (installerVersion != null) {
            Mockito.`when`(installer.version).thenReturn(installerVersion)
        }
        return installer
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
        onInstall.run()
        return true
    }

    override fun optimisticSwap(
        installer: Installer,
        redefiners: Map<Int, ClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: JuggOverlayUpdate,
        adb: AdbClient,
        logger: ILogger,
        isPushOverlayOnly: Boolean,
    ): OverlayId {
        return when (optimisticSwapPolicy) {
            OptimisticSwapPolicy.FORBIDDEN -> throw AssertionError(
                "Apply Changes optimisticSwap must not be called in deploy-flow direct overlay tests",
            )
            OptimisticSwapPolicy.RECORD_SUCCESS -> {
                optimisticSwapInvokeCount++
                OverlayId.builder(overlayUpdate.cachedDump.overlayId).build()
            }
        }
    }

    enum class OptimisticSwapPolicy {
        FORBIDDEN,
        RECORD_SUCCESS,
    }
}
