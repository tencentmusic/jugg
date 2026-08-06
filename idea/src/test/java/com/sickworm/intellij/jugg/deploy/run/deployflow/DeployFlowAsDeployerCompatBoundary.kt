package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.android.tools.deployer.Installer
import com.android.tools.deployer.OverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession
import com.sickworm.intellij.jugg.deploy.run.JuggClassRedefiner
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

    var makeDebuggerRedefinersInvokeCount: Int = 0
        private set

    var createInstallSessionInvokeCount: Int = 0
        private set

    val optimisticSwapRestartArgs: MutableList<Boolean> = mutableListOf()

    override fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession {
        createInstallSessionInvokeCount++
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
        return when (optimisticSwapPolicy) {
            OptimisticSwapPolicy.FORBIDDEN -> throw AssertionError(
                "Apply Changes optimisticSwap must not be called in deploy-flow direct overlay tests",
            )
            OptimisticSwapPolicy.RECORD_SUCCESS -> {
                optimisticSwapInvokeCount++
                optimisticSwapRestartArgs += argRestart
                val rawOverlayId = OverlayId.builder(overlayUpdate.cachedDump.overlayId.raw as OverlayId).build()
                JuggOverlayId(rawOverlayId, rawOverlayId.sha, rawOverlayId.isBaseInstall)
            }
            OptimisticSwapPolicy.FAIL_SECOND_AFTER_RECORD -> {
                optimisticSwapInvokeCount++
                optimisticSwapRestartArgs += argRestart
                if (optimisticSwapInvokeCount >= 2) {
                    throw IllegalStateException("AGENT_ATTACH_FAILED")
                }
                val rawOverlayId = OverlayId.builder(overlayUpdate.cachedDump.overlayId.raw as OverlayId).build()
                virtualDevice.writeOverlayId(rawOverlayId.sha)
                virtualDevice.writeOverlayFile("base.apk/res/layout/partial_slice.xml", byteArrayOf(1))
                JuggOverlayId(rawOverlayId, rawOverlayId.sha, rawOverlayId.isBaseInstall)
            }
        }
    }

    override fun makeDebuggerRedefiners(
        project: com.intellij.openapi.project.Project,
        device: IDevice,
        fallback: Boolean,
    ): Map<Int, JuggClassRedefiner> {
        makeDebuggerRedefinersInvokeCount++
        return emptyMap()
    }

    enum class OptimisticSwapPolicy {
        FORBIDDEN,
        RECORD_SUCCESS,
        FAIL_SECOND_AFTER_RECORD,
    }
}
