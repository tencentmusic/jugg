package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DeployStateManagerTest {

    @Test
    fun `deploy state cache should distinguish devices with the same display name`() {
        val targetManager: IDeployTargetManager = mock()
        val historyManager: IDeployHistoryManager = mock()
        val hostResolver: IHostDeployStateResolver = mock()
        val firstDevice: IDevice = mock()
        val secondDevice: IDevice = mock()
        whenever(firstDevice.name).thenReturn("Pixel 9")
        whenever(firstDevice.serialNumber).thenReturn("serial-a")
        whenever(secondDevice.name).thenReturn("Pixel 9")
        whenever(secondDevice.serialNumber).thenReturn("serial-b")
        whenever(historyManager.hasBeenFullCompiled).thenReturn(true)
        whenever(targetManager.getPackageNameOrNull()).thenReturn("com.example")
        whenever(hostResolver.resolve(firstDevice, "com.example")).thenReturn(IdeDeployState.ok)
        whenever(hostResolver.resolve(secondDevice, "com.example")).thenReturn(IdeDeployState.deviceNotConnected)
        val manager = DeployStateManager(targetManager, historyManager, hostResolver, Logger.getInstance("DeployStateManagerTest"))

        assertEquals(JuggDeployState.READY, manager.updateDeployState(firstDevice))
        assertEquals(IdeDeployState.deviceNotConnected, manager.getDeployState(secondDevice).ideDeployState)
    }

    private fun createManager(): DeployStateManager {
        return DeployStateManager(
            deployTargetManager = mock(),
            deployHistoryManager = mock(),
            hostDeployStateResolver = mock(),
            logger = Logger.getInstance("DeployStateManagerTest"),
        )
    }

    @Test
    fun `waitForPendingFileProcessing should report timeout when pending task not finished`() {
        val manager = createManager()
        manager.beginFileProcessing()

        val start = System.currentTimeMillis()
        val outcome = manager.waitForPendingFileProcessing(timeoutMs = 50L)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(outcome.isTimeout)
        assertTrue(outcome.pendingCount > 0)
        assertTrue("elapsed=$elapsed", elapsed >= 40L)
    }

    @Test
    fun `waitForPendingFileProcessing should return ready when pending task is finished`() {
        val manager = createManager()
        manager.beginFileProcessing()

        val worker = Thread {
            Thread.sleep(30L)
            manager.endFileProcessing()
        }
        worker.start()

        val outcome = manager.waitForPendingFileProcessing(timeoutMs = 500L)
        worker.join(1000L)

        assertFalse(outcome.isTimeout)
        assertEquals(0, outcome.pendingCount)
    }
}
