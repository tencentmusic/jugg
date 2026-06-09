package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.android.ddmlib.ClientData
import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.ProgressIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class AndroidDebugClientReadyWaiterTest {

    @Test
    fun `waits for client debugger waiting status using Android Studio utility`() {
        val expectedClient = Mockito.mock(Client::class.java)
        val device = Mockito.mock(IDevice::class.java)
        FakeDebugClientReadyUtils.client = expectedClient

        val client = AndroidDebugClientReadyWaiter(FakeDebugClientReadyUtils::class.java.name)
            .waitForWaitingDebuggerClient(device, "com.example.app")

        assertSame(expectedClient, client)
        assertSame(device, FakeDebugClientReadyUtils.lastDevice)
        assertEquals(listOf("com.example.app"), FakeDebugClientReadyUtils.lastAppIds)
        assertEquals(15L, FakeDebugClientReadyUtils.lastTimeoutSeconds)
        assertTrue(FakeDebugClientReadyUtils.lastIndicator is ProgressIndicator)
        assertEquals(ClientData.DebuggerStatus.WAITING, FakeDebugClientReadyUtils.lastWaitingProcessState)
    }

    class FakeDebugClientReadyUtils {
        companion object {
            lateinit var client: Client
            var lastDevice: IDevice? = null
            var lastAppIds: Collection<String>? = null
            var lastTimeoutSeconds: Long? = null
            var lastIndicator: ProgressIndicator? = null
            var lastWaitingProcessState: ClientData.DebuggerStatus? = null

            @JvmStatic
            fun waitForClientReadyForDebug(
                device: IDevice,
                appIds: Collection<String>,
                timeoutSeconds: Long,
                indicator: ProgressIndicator,
                waitingProcessState: ClientData.DebuggerStatus,
            ): Client {
                lastDevice = device
                lastAppIds = appIds
                lastTimeoutSeconds = timeoutSeconds
                lastIndicator = indicator
                lastWaitingProcessState = waitingProcessState
                return client
            }
        }
    }
}
