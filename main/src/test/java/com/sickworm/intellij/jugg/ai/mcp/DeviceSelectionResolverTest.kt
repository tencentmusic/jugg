package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class DeviceSelectionResolverTest {
    private var originalPlatformApi: IPlatformApi? = null

    @Before
    fun setUp() {
        originalPlatformApi = runCatching { PlatformApi.impl }.getOrNull()
    }

    @After
    fun tearDown() {
        originalPlatformApi?.let { PlatformApi.impl = it }
    }

    @Test
    fun testExplicitSerialSelectsExactOnlineDevice() {
        val first = device("device-1")
        val second = device("device-2")
        installPlatformApi(mapOf(first to adb("device-1", true), second to adb("device-2", true)))
        val manager = FakeDeployTargetManager(listOf(first), listOf(first, second))

        val result = DeviceSelectionResolver().resolve(manager, "device-2")

        assertTrue(result is DeviceSelectionResult.Selected)
        assertEquals(second, (result as DeviceSelectionResult.Selected).device)
    }

    @Test
    fun testExplicitSerialDoesNotFallbackToSelectedDevice() {
        val selected = device("device-1")
        installPlatformApi(mapOf(selected to adb("device-1", true)))
        val manager = FakeDeployTargetManager(listOf(selected), listOf(selected))

        val result = DeviceSelectionResolver().resolve(manager, "missing")

        assertTrue(result is DeviceSelectionResult.NoDevice)
        assertTrue((result as DeviceSelectionResult.NoDevice).messageDetail.contains("missing"))
    }

    @Test
    fun testExplicitSerialRejectsOfflineDevice() {
        val selected = device("device-1")
        val offline = device("device-2")
        installPlatformApi(mapOf(selected to adb("device-1", true), offline to adb("device-2", false)))
        val manager = FakeDeployTargetManager(listOf(selected), listOf(selected, offline))

        val result = DeviceSelectionResolver().resolve(manager, "device-2")

        assertTrue(result is DeviceSelectionResult.NoDevice)
    }

    private fun device(serial: String): IDevice {
        return Mockito.mock(IDevice::class.java).also {
            Mockito.`when`(it.serialNumber).thenReturn(serial)
            Mockito.`when`(it.isOnline).thenReturn(true)
        }
    }

    private fun adb(serial: String, isOnline: Boolean): IDeviceAdb {
        return Mockito.mock(IDeviceAdb::class.java).also {
            Mockito.`when`(it.serial).thenReturn(serial)
            Mockito.`when`(it.isOnline).thenReturn(isOnline)
        }
    }

    private fun installPlatformApi(adbByDevice: Map<IDevice, IDeviceAdb>) {
        val platformApi = Mockito.mock(IPlatformApi::class.java)
        adbByDevice.forEach { (device, adb) ->
            Mockito.`when`(platformApi.toDeviceAdb(device)).thenReturn(adb)
        }
        PlatformApi.impl = platformApi
    }

    private class FakeDeployTargetManager(
        private val selected: List<IDevice>,
        private val connected: List<IDevice>,
    ) : IDeployTargetManager {
        override fun setApks(apks: List<ApkInfo>) = Unit
        override fun getApks(): List<ApkInfo> = emptyList()
        override fun getSelectedDevices(): List<IDevice> = selected
        override fun getConnectedDevices(): List<IDevice> = connected
        override fun startApp(device: IDevice): Boolean = false
        override fun restartApp(device: IDevice): Boolean = false
        override fun stopApp(device: IDevice): Boolean = false
        override fun isAppForeground(device: IDevice): Boolean = false
        override fun getPackageName(): String = "com.example"
    }
}
