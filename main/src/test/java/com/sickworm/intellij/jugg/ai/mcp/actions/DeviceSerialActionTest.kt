package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.AndroidVersion
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class DeviceSerialActionTest {
    @Test
    fun testDevicesReturnsOnlyExplicitOnlineSerial() {
        val first = device("device-1")
        val second = device("device-2")
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
        val device = device("device-1")
        val manager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(manager.getConnectedDevices()).thenReturn(listOf(device))

        val result = DeviceListMcpToolAction().execute(
            mapOf("projectDir" to "/project", "serial" to "missing"), runtime(manager),
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
    }

    @Test
    fun testDevicesListsConnectedDevicesWhenSelectedDeviceIsUnavailable() {
        val device = device("device-1")
        val manager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(manager.getSelectedDevices()).thenThrow(IllegalStateException("Selected device is not online"))
        Mockito.`when`(manager.getConnectedDevices()).thenReturn(listOf(device))

        val result = DeviceListMcpToolAction().execute(
            mapOf("projectDir" to "/project"), runtime(manager),
        )

        assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val devices = (result.data as Map<String, Any>)["devices"] as List<Map<String, Any>>
        assertEquals(listOf("device-1"), devices.map { it["serial"] })
        assertEquals(false, devices.single()["isSelected"])
    }

    @Test
    fun testStatusUsesExplicitDeviceState() {
        val target = device("device-2")
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

    private fun device(serial: String): IDevice {
        return Mockito.mock(IDevice::class.java).also {
            Mockito.`when`(it.serialNumber).thenReturn(serial)
            Mockito.`when`(it.name).thenReturn(serial)
            Mockito.`when`(it.isOnline).thenReturn(true)
            Mockito.`when`(it.version).thenReturn(AndroidVersion(34))
        }
    }
}
