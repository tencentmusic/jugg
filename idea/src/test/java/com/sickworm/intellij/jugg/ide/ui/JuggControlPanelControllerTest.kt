package com.sickworm.intellij.jugg.ide.ui

import com.android.ddmlib.IDevice
import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class JuggControlPanelControllerTest {
    private val scopeJob = Job()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val queryStarted = CountDownLatch(1)
    private val releaseQuery = CountDownLatch(1)
    private val queryCount = AtomicInteger()
    private val targetManager = Mockito.mock(IDeployTargetManager::class.java)
    private lateinit var controller: JuggControlPanelController
    private var oldCompatRecords = ""

    @Before
    fun setUp() {
        TestGlobal.init()
        oldCompatRecords = JuggSettings.deviceCompatRecordJson
        JuggSettings.deviceCompatRecordJson = "[]"
        val project = Mockito.mock(Project::class.java)
        Mockito.doReturn(Mockito.mock(RunManager::class.java)).`when`(project).getService(RunManager::class.java)
        val manager = Mockito.mock(JuggManager::class.java)
        Mockito.`when`(manager.coroutineContext).thenReturn(scope.coroutineContext)
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.getProperty("ro.product.manufacturer")).thenReturn("Google")
        Mockito.`when`(device.getProperty("ro.product.model")).thenReturn("Pixel 8")
        Mockito.`when`(targetManager.getDeviceNameList()).thenReturn("Pixel 8")
        Mockito.`when`(targetManager.getConnectedDevices()).thenReturn(listOf(device))
        controller = JuggControlPanelController(
            project = project,
            manager = manager,
            deployTargetManager = targetManager,
            deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
            deployFileManager = Mockito.mock(DeployFileManager::class.java),
            logger = TestGlobal.logger,
        )
    }

    @After
    fun tearDown() {
        releaseQuery.countDown()
        awaitDeviceRefresh()
        scope.cancel()
        SwingUtilities.invokeAndWait {}
        JuggSettings.deviceCompatRecordJson = oldCompatRecords
    }

    @Test
    fun `panel refresh stays responsive while selected devices are unavailable`() {
        Mockito.`when`(targetManager.getDeviceNameList()).thenAnswer {
            waitForDevices()
            "Pixel 8"
        }

        assertResponsiveRefresh { controller.refresh() }
    }

    @Test
    fun `settings refresh stays responsive while connected devices are unavailable`() {
        val devices = targetManager.getConnectedDevices()
        Mockito.`when`(targetManager.getConnectedDevices()).thenAnswer {
            waitForDevices()
            devices
        }

        assertResponsiveRefresh { controller.refreshSettings() }
    }

    @Test
    fun `cleared panel ignores a pending device result`() {
        Mockito.`when`(targetManager.getDeviceNameList()).thenAnswer {
            waitForDevices()
            "Pixel 8"
        }
        val uiReturned = CountDownLatch(1)
        SwingUtilities.invokeLater {
            controller.refresh()
            uiReturned.countDown()
        }
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
        assertTrue("The UI must not wait for devices", uiReturned.await(2, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait { controller.clear() }

        releaseQuery.countDown()
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        assertTrue(controller.model.snapshot().context.devices.isEmpty())
        assertTrue(controller.model.snapshot().settings.forceCompatDevices.isEmpty())
    }

    @Test
    fun `selected device API failure preserves connected devices and manager scope`() {
        Mockito.`when`(targetManager.getDeviceNameList()).thenThrow(NoClassDefFoundError("Unavailable device API"))

        SwingUtilities.invokeAndWait { controller.refreshSettings() }
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        assertTrue(scopeJob.isActive)
        assertEquals(listOf("Google Pixel 8"), controller.model.snapshot().settings.forceCompatDevices.map { it.displayName })
    }

    @Test
    fun `connected device API failure preserves selected devices and manager scope`() {
        Mockito.`when`(targetManager.getConnectedDevices()).thenThrow(NoClassDefFoundError("Unavailable bridge API"))

        SwingUtilities.invokeAndWait { controller.refreshSettings() }
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        assertTrue(scopeJob.isActive)
        assertEquals(listOf("Pixel 8"), controller.model.snapshot().context.devices)
        assertTrue(controller.model.snapshot().settings.forceCompatDevices.isEmpty())
    }

    private fun assertResponsiveRefresh(refresh: () -> Unit) {
        val uiReturned = CountDownLatch(1)
        SwingUtilities.invokeLater {
            refresh()
            controller.refreshSettings()
            controller.refreshSettings()
            uiReturned.countDown()
        }
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
        assertTrue("The UI must not wait for devices", uiReturned.await(2, TimeUnit.SECONDS))
        assertTrue(controller.model.snapshot().settings.forceCompatDevices.isEmpty())

        releaseQuery.countDown()
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        val snapshot = controller.model.snapshot()
        assertEquals(listOf("Pixel 8"), snapshot.context.devices)
        assertEquals(listOf("Google Pixel 8"), snapshot.settings.forceCompatDevices.map { it.displayName })
        assertEquals("Repeated refreshes must share the pending device query", 1, queryCount.get())
    }

    private fun waitForDevices() {
        queryCount.incrementAndGet()
        queryStarted.countDown()
        assertTrue("Device query was not released", releaseQuery.await(10, TimeUnit.SECONDS))
    }

    private fun awaitDeviceRefresh() = runBlocking {
        withTimeout(5_000) { scopeJob.children.toList().joinAll() }
    }
}
