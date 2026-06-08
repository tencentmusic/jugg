package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.Installer
import com.android.tools.deployer.OverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.android.tools.deployer.ClassRedefiner
import com.android.utils.ILogger
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
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

    override fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession {
        val installer = Mockito.mock(Installer::class.java)
        if (installerVersion != null) {
            Mockito.`when`(installer.version).thenReturn(installerVersion)
        }
        return JuggInstallSession(installer, installerVersion, onPrompt, onMessage)
    }

    override fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        onInstall.run()
        return true
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
        return when (optimisticSwapPolicy) {
            OptimisticSwapPolicy.FORBIDDEN -> throw AssertionError(
                "Apply Changes optimisticSwap must not be called in deploy-flow direct overlay tests",
            )
            OptimisticSwapPolicy.RECORD_SUCCESS -> {
                optimisticSwapInvokeCount++
                val rawOverlayId = OverlayId.builder(overlayUpdate.cachedDump.overlayId.raw as OverlayId).build()
                JuggOverlayId(rawOverlayId, rawOverlayId.sha, rawOverlayId.isBaseInstall)
            }
        }
    }

    enum class OptimisticSwapPolicy {
        FORBIDDEN,
        RECORD_SUCCESS,
    }
}
