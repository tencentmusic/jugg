package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class DeviceSerialActionTest {
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
    fun testDevicesReturnsOnlyExplicitOnlineSerial() {
        val first = device()
        val second = device()
        installPlatformApi(
            mapOf(first to adb("device-1", true), second to adb("device-2", true)),
        )
        val manager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(manager.getConnectedDevices()).thenReturn(listOf(first, second))

        val result = DeviceListMcpToolAction().execute(
            mapOf("projectDir" to "/project", "serial" to "device-2"), runtime(manager),
        )

        assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val devices = (result.data as Map<String, Any>)["devices"] as List<Map<String, Any>>
        assertEquals(listOf("device-2"), devices.map { it["serial"] })
        assertEquals(true, devices.single()["isSelected"])
    }

    @Test
    fun testDevicesReturnsNoDeviceForMissingExplicitSerial() {
        val device = device()
        installPlatformApi(mapOf(device to adb("device-1", true)))
        val manager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(manager.getConnectedDevices()).thenReturn(listOf(device))

        val result = DeviceListMcpToolAction().execute(
            mapOf("projectDir" to "/project", "serial" to "missing"), runtime(manager),
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
    }

    @Test
    fun testStatusUsesExplicitDeviceState() {
        val target = device()
        installPlatformApi(mapOf(target to adb("device-2", true)))
        val manager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(manager.getTargetDevices("device-2")).thenReturn(listOf(target))
        val stateManager = Mockito.mock(IDeployStateManager::class.java)
        Mockito.`when`(stateManager.updateDeployState(target)).thenReturn(
            JuggDeployState(
                state = JuggDeployState.State.READY_DEPLOY,
                msg = "device-2 ready",
                ideDeployState = IdeDeployState.ok,
            ),
        )

        val result = GetStatusMcpToolAction().execute(
            mapOf("projectDir" to "/project", "serial" to "device-2"), runtime(manager, stateManager),
        )

        assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        assertEquals(true, data["hasDevice"])
        assertEquals("device-2 ready", data["stateMessage"])
        Mockito.verify(stateManager).updateDeployState(target)
    }

    private fun runtime(
        manager: IDeployTargetManager,
        stateManager: IDeployStateManager? = null,
    ): com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger = com.intellij.openapi.diagnostic.Logger.getInstance("DeviceSerialActionTest")
            override val projectDir: String = "/project"
            override val deployTargetManager: IDeployTargetManager = manager
            override val deployStateManager: IDeployStateManager? = stateManager
            override val forceGradleCompileHelper = FakeForceGradleCompileHelper()
            override val juggConfigurationRunner = FakeJuggConfigurationRunner()
        }
    }

    private fun device(): IDevice {
        return Mockito.mock(IDevice::class.java).also {
            Mockito.`when`(it.isOnline).thenReturn(true)
        }
    }

    private fun adb(serial: String, isOnline: Boolean): IDeviceAdb {
        return Mockito.mock(IDeviceAdb::class.java).also {
            Mockito.`when`(it.serial).thenReturn(serial)
            Mockito.`when`(it.displayName).thenReturn(serial)
            Mockito.`when`(it.api).thenReturn(34)
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
}
