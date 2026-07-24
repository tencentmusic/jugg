package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TaskRunnerManagerTest {

    @Test
    fun `successful task is not reported`() {
        val juggServer = mock<JuggServer>()
        val manager = createManager(juggServer)

        manager.runTaskSafe(
            "Successful task",
            Runnable {},
            isNeedShowIndicator = false,
            isBlockIncrementalCompile = false,
        )

        verify(juggServer, never()).report(any<ReportEventData>())
    }

    @Test
    fun `failed task is reported`() {
        val juggServer = mock<JuggServer>()
        val manager = createManager(juggServer)

        manager.runTaskSafe(
            "Failed task",
            Runnable { throw IllegalStateException("boom") },
            isNeedShowIndicator = false,
            isBlockIncrementalCompile = false,
        )

        val reportCaptor = argumentCaptor<ReportEventData>()
        verify(juggServer).report(reportCaptor.capture())
        assertEquals("Failed task", reportCaptor.firstValue.action)
        assertFalse(reportCaptor.firstValue.isSuccess)
        assertEquals("boom", reportCaptor.firstValue.detail)
    }

    private fun createManager(juggServer: JuggServer): TaskRunnerManager {
        return TaskRunnerManager(
            mock(),
            TestGlobal.getLogger(),
            mock<DeployStateManager>(),
            juggServer,
            CoroutineScope(Dispatchers.Unconfined),
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }
}
