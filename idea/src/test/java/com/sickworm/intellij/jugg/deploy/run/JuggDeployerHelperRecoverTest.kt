package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class JuggDeployerHelperRecoverTest {

    @Test
    fun `mergeOverlayIds should keep existing package ids when library test apk is backfilled`() {
        val currentIds = mapOf(
            "com.example.myapplication" to "base-overlay",
            "com.example.myapplication.test" to "app-test-overlay",
        )
        val backfilledIds = mapOf(
            "com.example.library1.test" to "library-test-overlay",
        )

        val result = JuggDeployerHelper.mergeOverlayIds(currentIds, backfilledIds)

        assertEquals(
            mapOf(
                "com.example.myapplication" to "base-overlay",
                "com.example.myapplication.test" to "app-test-overlay",
                "com.example.library1.test" to "library-test-overlay",
            ),
            result,
        )
    }

    @Test
    fun `tryDryDeploy should skip app launch when app is not installed`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(false)

        val helper = JuggDeployerHelper(
            project = Mockito.mock(Project::class.java),
            deployTargetManager = deployTargetManager,
            deployFileManager = Mockito.mock(DeployFileManager::class.java),
            deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
            deployStateManager = Mockito.mock(DeployStateManager::class.java),
            dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java),
            compileContextManager = Mockito.mock(CompileContextManager::class.java),
            juggServer = Mockito.mock(JuggServer::class.java),
            taskRunnerManager = Mockito.mock(TaskRunnerManager::class.java),
            logger = Mockito.mock(Logger::class.java),
        )

        val result = invokeTryDryDeploy(helper, device)

        assertEquals("APP_NOT_INSTALLED", result.toString())
        Mockito.verify(deployTargetManager, Mockito.never()).restartApp(device)
    }

    private fun invokeTryDryDeploy(helper: JuggDeployerHelper, device: IDevice): Any {
        val method = JuggDeployerHelper::class.java.getDeclaredMethod(
            "tryDryDeploy",
            IDevice::class.java,
            Boolean::class.javaPrimitiveType,
            CompileUiHandler::class.java,
        )
        method.isAccessible = true
        return method.invoke(helper, device, false, CompileUiHandler.DEFAULT)
    }
}
