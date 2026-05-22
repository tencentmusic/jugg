package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost
import com.sickworm.intellij.jugg.server.JuggServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class DeployRetryHandlerTest {

    @Test
    fun `tryRetry should redeploy with compat data when compat fallback is required`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployOptions = DeployOptions(device = device, isLastDevice = true)
        val deployData = JuggDeployData.forInstall(emptyList())
        val compatData = JuggDeployData.forInstall(emptyList()).copy(isCompatDeploy = true)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        Mockito.`when`(deployFileManager.appendCompatDeployFiles(deployData)).thenReturn(compatData)

        val deployRunHost = RecordingDeployRunHost(DeployTaskResult(isSuccess = true, costTime = 1L))
        val handler = createHandler(
            deployFileManager = deployFileManager,
            deployRunHost = deployRunHost,
            deployTargetManager = foregroundAwareTargetManager(device, isForeground = false),
        )

        val result = handler.tryRetry(
            deployOptions,
            finalIsFallbackAllHotFix = false,
            deployData = deployData,
            reason = DeployRetryHandler.REDEPLOY_WITH_COMPAT_MESSAGE,
        )

        assertEquals(deployRunHost.lastResult, result)
        assertNotNull(deployRunHost.lastRedeployOptions)
        assertEquals(compatData, deployRunHost.lastRedeployOptions?.retryDeployData)
        assertEquals(DeployRetryHandler.REDEPLOY_WITH_COMPAT_MESSAGE, deployRunHost.lastRedeployOptions?.retryReason)
        assertEquals(true, deployRunHost.lastRedeployOptions?.isSkipExceptOverlayCheck)
    }

    @Test
    fun `tryRetry should return null when deploy timeout exceeded max retries`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployOptions = DeployOptions(device = device, isLastDevice = true, timeOutRetryTimes = 3)
        val deployData = JuggDeployData.forInstall(emptyList())

        val handler = createHandler(deployTargetManager = foregroundAwareTargetManager(device, isForeground = false))

        val result = handler.tryRetry(
            deployOptions,
            finalIsFallbackAllHotFix = false,
            deployData = deployData,
            reason = "MessagePipeWrapper read() timeout (5000ms)",
        )

        assertNull(result)
    }

    @Test
    fun `tryRetry should redeploy with hot fix fallback on instrumentation failed`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployOptions = DeployOptions(device = device, isLastDevice = true)
        val deployData = JuggDeployData.forInstall(emptyList())

        val deployRunHost = RecordingDeployRunHost(DeployTaskResult(isSuccess = true, costTime = 2L))
        val handler = createHandler(
            deployRunHost = deployRunHost,
            deployTargetManager = foregroundAwareTargetManager(device, isForeground = false),
        )

        val result = handler.tryRetry(
            deployOptions,
            finalIsFallbackAllHotFix = false,
            deployData = deployData,
            reason = "INSTRUMENTATION_FAILED",
        )

        assertEquals(deployRunHost.lastResult, result)
        assertEquals(deployData.toFallbackToHotFixData(), deployRunHost.lastRedeployOptions?.retryDeployData)
        assertEquals("INSTRUMENTATION_FAILED", deployRunHost.lastRedeployOptions?.retryReason)
    }

    private fun createHandler(
        deployTargetManager: IDeployTargetManager = Mockito.mock(IDeployTargetManager::class.java),
        deployFileManager: DeployFileManager = Mockito.mock(DeployFileManager::class.java),
        deployStateRecover: DeployStateRecover = Mockito.mock(DeployStateRecover::class.java),
        deployRunHost: IJuggDeployHelperRunHost = RecordingDeployRunHost(DeployTaskResult(isSuccess = true, costTime = 0)),
    ): DeployRetryHandler {
        return DeployRetryHandler(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployStateRecover = deployStateRecover,
            juggServer = Mockito.mock(JuggServer::class.java),
            deployRunHost = deployRunHost,
            logger = Mockito.mock(Logger::class.java),
        )
    }

    private fun foregroundAwareTargetManager(device: IDevice, isForeground: Boolean): IDeployTargetManager {
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppForeground(device)).thenReturn(isForeground)
        return deployTargetManager
    }

    private class RecordingDeployRunHost(
        private val result: DeployTaskResult,
    ) : IJuggDeployHelperRunHost {
        var lastRedeployOptions: DeployOptions? = null
        val lastResult: DeployTaskResult get() = result

        override fun runRecoverDeployTask(
            device: IDevice,
            data: JuggDeployData,
            isSkipExceptOverlayCheck: Boolean,
            compileUiHandler: CompileUiHandler,
        ) = Unit

        override fun redeploy(deployOptions: DeployOptions): DeployTaskResult {
            lastRedeployOptions = deployOptions
            return result
        }

        override fun tryRetryInstall(
            deployOptions: DeployOptions,
            deployData: JuggDeployData,
            reason: String,
        ): DeployTaskResult? = null

        override fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean = false
    }
}
