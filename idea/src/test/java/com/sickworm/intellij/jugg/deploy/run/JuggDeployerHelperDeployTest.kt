package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito

/**
 * Orchestration tests for [JuggDeployerHelper.deploy] early-exit branches.
 */
class JuggDeployerHelperDeployTest {

    @Test
    fun `deploy should fail immediately when process handler is canceled`() {
        val device = Mockito.mock(IDevice::class.java)
        val processHandler = Mockito.mock(IProcessHandler::class.java)
        Mockito.`when`(processHandler.isCanceled).thenReturn(true)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)

        val helper = createHelper(deployStateManager = deployStateManager)
        val result = helper.deploy(
            DeployOptions(
                device = device,
                isLastDevice = true,
                processHandler = processHandler,
            ),
        )

        assertFalse(result.isSuccess)
        assertEquals("deploy canceled", result.failedReason)
    }

    @Test
    fun `deploy should fail when incremental deploy has no connected device`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(false)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
        )
        val result = helper.deploy(
            DeployOptions(device = device, isLastDevice = true, isInstall = false),
        )

        assertFalse(result.isSuccess)
        assertEquals("no device connected", result.failedReason)
    }

    @Test
    fun `deploy should fail when warm up requested but device is not deploy ready`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)

        val notReady = JuggDeployState(
            JuggDeployState.State.READY_INCREMENTAL_COMPILE,
            "not ready",
            IdeDeployState.ok,
        )
        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(notReady)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(notReady)

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
        )
        val result = helper.deploy(
            DeployOptions(device = device, isLastDevice = true, isWarmUp = true),
        )

        assertFalse(result.isSuccess)
        assertEquals("device not ready to warm up", result.failedReason)
    }

    private fun createHelper(
        deployTargetManager: IDeployTargetManager = Mockito.mock(IDeployTargetManager::class.java),
        deployStateManager: DeployStateManager = Mockito.mock(DeployStateManager::class.java),
    ): JuggDeployerHelper {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/jugg-deploy-test")

        val compileContext = Mockito.mock(ICompileContext::class.java)
        Mockito.`when`(compileContext.isDebuggable).thenReturn(true)
        val compileContextManager = Mockito.mock(CompileContextManager::class.java)
        Mockito.`when`(compileContextManager.compileContext).thenReturn(compileContext)

        return JuggDeployerHelper(
            project = project,
            deployTargetManager = deployTargetManager,
            deployFileManager = Mockito.mock(DeployFileManager::class.java),
            deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
            deployStateManager = deployStateManager,
            dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java),
            compileContextManager = compileContextManager,
            juggServer = Mockito.mock(JuggServer::class.java),
            taskRunnerManager = Mockito.mock(TaskRunnerManager::class.java),
            logger = Mockito.mock(Logger::class.java),
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
            JuggSettings.isEmbeddedToApk = false
        }
    }
}
