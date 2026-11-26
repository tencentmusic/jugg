package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.api.IDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class DeviceSelectionResolverTest {
    @Test
    fun testExplicitSerialSelectsExactOnlineDevice() {
        val first = device("device-1")
        val second = device("device-2")
        val manager = FakeDeployTargetManager(listOf(first), listOf(first, second))

        val result = DeviceSelectionResolver().resolve(manager, "device-2")

        assertTrue(result is DeviceSelectionResult.Selected)
        assertEquals(second, (result as DeviceSelectionResult.Selected).device)
    }

    @Test
    fun testExplicitSerialDoesNotFallbackToSelectedDevice() {
        val selected = device("device-1")
        val manager = FakeDeployTargetManager(listOf(selected), listOf(selected))

        val result = DeviceSelectionResolver().resolve(manager, "missing")

        assertTrue(result is DeviceSelectionResult.NoDevice)
        assertTrue((result as DeviceSelectionResult.NoDevice).messageDetail.contains("missing"))
    }

    @Test
    fun testMultipleSelectedDevicesRequireExplicitSerialWithoutAdbAdapter() {
        val first = device("device-1")
        val second = device("device-2")
        val manager = FakeDeployTargetManager(listOf(first, second), listOf(first, second))

        val result = DeviceSelectionResolver().resolve(manager)

        assertTrue(result is DeviceSelectionResult.MultipleDevices)
        result as DeviceSelectionResult.MultipleDevices
        assertTrue(result.messageDetail.contains("--serial"))
    }

    private fun device(serial: String): IDevice {
        return Mockito.mock(IDevice::class.java).also {
            Mockito.`when`(it.serialNumber).thenReturn(serial)
            Mockito.`when`(it.isOnline).thenReturn(true)
        }
    }

    private class FakeDeployTargetManager(
        private val selected: List<IDevice>,
        private val connected: List<IDevice>,
    ) : IDeployTargetManager {
        override fun setApks(apks: List<ApkInfo>) = Unit
        override fun getApks(): List<ApkInfo> = emptyList()
        override fun getSelectedDevices(): List<IDevice> = selected
        override fun getConnectedDevices(): List<IDevice> = connected
        override fun createDeviceAdb(device: IDevice): IDeviceAdb = throw UnsupportedOperationException()
        override fun startApp(device: IDevice): Boolean = false
        override fun restartApp(device: IDevice): Boolean = false
        override fun stopApp(device: IDevice): Boolean = false
        override fun isAppForeground(device: IDevice): Boolean = false
        override fun getPackageName(): String = "com.example"
    }
}
