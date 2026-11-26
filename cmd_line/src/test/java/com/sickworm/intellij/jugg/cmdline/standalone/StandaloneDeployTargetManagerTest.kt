package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeviceManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneDeployTargetManagerTest {

    @Test
    fun `default selection returns all online devices`() {
        assumeTrue(System.getenv("ANDROID_SERIAL").isNullOrBlank())
        val first = device("device-1")
        val second = device("device-2")
        val deviceManager = Mockito.mock(StandaloneDeviceManager::class.java)
        Mockito.`when`(deviceManager.devices()).thenReturn(listOf(second, first))
        val manager = StandaloneDeployTargetManager(
            deviceManagerProvider = { deviceManager },
            environmentProvider = { Mockito.mock(IDeployHost::class.java) },
            logger = Logger.getInstance("StandaloneDeployTargetManagerTest"),
        )

        assertEquals(listOf(first, second), manager.getSelectedDevices())
    }

    @Test
    fun `error logs include every target device`() {
        assumeTrue(System.getenv("ANDROID_SERIAL").isNullOrBlank())
        val first = device("device-1")
        val second = device("device-2")
        val deviceManager = Mockito.mock(StandaloneDeviceManager::class.java)
        Mockito.`when`(deviceManager.devices()).thenReturn(listOf(first, second))
        val host = Mockito.mock(IDeployHost::class.java)
        val logger = Logger.getInstance("StandaloneDeployTargetManagerTest")
        val firstAdb = adb("first log")
        val secondAdb = adb("second log")
        Mockito.`when`(host.createDeviceAdb(first, logger))
            .thenReturn(firstAdb)
        Mockito.`when`(host.createDeviceAdb(second, logger))
            .thenReturn(secondAdb)
        val manager = StandaloneDeployTargetManager(
            deviceManagerProvider = { deviceManager },
            environmentProvider = { host },
            logger = logger,
        )

        val logs = manager.dumpErrorLogs()

        assertTrue(logs.contains("Devices: [device-1, device-2]"))
        assertTrue(logs.contains("first log"))
        assertTrue(logs.contains("second log"))
    }

    private fun device(serial: String): IDevice {
        return Mockito.mock(IDevice::class.java).also {
            Mockito.`when`(it.serialNumber).thenReturn(serial)
            Mockito.`when`(it.name).thenReturn(serial)
            Mockito.`when`(it.isOnline).thenReturn(true)
        }
    }

    private fun adb(log: String): IDeviceAdb {
        return Mockito.mock(IDeviceAdb::class.java).also {
            Mockito.`when`(it.execAdbShellCmd("logcat -t100000")).thenReturn(log)
        }
    }
}
