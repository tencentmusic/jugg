package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class DeployStateManagerTest {

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
