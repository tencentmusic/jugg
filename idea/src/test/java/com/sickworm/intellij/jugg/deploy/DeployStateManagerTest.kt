package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.io.path.createTempDirectory

class DeployStateManagerTest {

    init {
        TestGlobal.init()
    }

    private fun createManager(): DeployStateManager {
        val project: Project = JuggMockProject(TestGlobal.projectInfo.projectRoot)
        JuggLogger.register(project, createTempDirectory("deploy_state_manager_test_log").toFile())
        return DeployStateManager(
            project = project,
            deployTargetManager = mock<IDeployTargetManager>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            ideDeployStateHelper = mock<IIdeDeployStateHelper>(),
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
