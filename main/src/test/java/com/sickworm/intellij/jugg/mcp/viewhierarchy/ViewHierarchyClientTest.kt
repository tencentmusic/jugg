package com.sickworm.intellij.jugg.mcp.viewhierarchy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import org.junit.Assert
import org.junit.Test
import java.io.File

class ViewHierarchyClientTest {

    @Test
    fun testResolveSocketCandidatesPreferMainProcessAndKeepAllPids() {
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "pidof com.example.app 2>/dev/null" to "111 222",
                "ps | grep \"com.example.app\"" to """
                    u0_a123      111   100  0  0 ?  00:00:00 com.example.app:worker
                    u0_a123      222   100  0  0 ?  00:00:00 com.example.app
                """.trimIndent(),
            ),
        )
        val client = StubViewHierarchyClient(adb, "com.example.app")

        val sockets = client.socketCandidates()

        Assert.assertEquals(listOf("jugg_vh_222", "jugg_vh_111", "jugg_vh"), sockets)
    }

    @Test
    fun testResolveSocketCandidatesFallbackToPsWhenPidofEmpty() {
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "pidof com.example.app 2>/dev/null" to "",
                "ps | grep \"com.example.app\"" to """
                    u0_a123      345   100  0  0 ?  00:00:00 com.example.app
                    u0_a123      456   100  0  0 ?  00:00:00 com.example.app:remote
                """.trimIndent(),
            ),
        )
        val client = StubViewHierarchyClient(adb, "com.example.app")

        val sockets = client.socketCandidates()

        Assert.assertEquals(listOf("jugg_vh_345", "jugg_vh_456", "jugg_vh"), sockets)
    }

    @Test
    fun testDumpLayoutTryNextPidSocketWhenPrimarySocketUnavailable() {
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "pidof com.example.app 2>/dev/null" to "111 222",
                "ps | grep \"com.example.app\"" to """
                    u0_a123      111   100  0  0 ?  00:00:00 com.example.app:worker
                    u0_a123      222   100  0  0 ?  00:00:00 com.example.app
                """.trimIndent(),
            ),
        )
        val client = StubViewHierarchyClient(
            adb = adb,
            packageName = "com.example.app",
            responsesBySocket = mapOf(
                "jugg_vh_222" to null,
                "jugg_vh_111" to okResponse(),
            ),
        )

        val result = client.dumpLayout()

        Assert.assertNotNull(result)
        Assert.assertEquals(listOf("jugg_vh_222", "jugg_vh_111"), client.attemptedSockets)
    }

    @Test
    fun testDumpLayoutTryLegacySocketAfterPidSocketsFail() {
        val client = StubViewHierarchyClient(
            adb = FakeDeviceAdb(emptyMap()),
            packageName = "com.example.app",
            socketCandidates = listOf("jugg_vh_100", "jugg_vh_200", "jugg_vh"),
            responsesBySocket = mapOf(
                "jugg_vh_100" to null,
                "jugg_vh_200" to null,
                "jugg_vh" to okResponse(),
            ),
        )

        val result = client.dumpLayout()

        Assert.assertNotNull(result)
        Assert.assertEquals(listOf("jugg_vh_100", "jugg_vh_200", "jugg_vh"), client.attemptedSockets)
    }

    private fun okResponse(): ViewHierarchyResponse {
        return ViewHierarchyResponse(
            status = "ok",
            message = null,
            data = jsonObject("""{"windows":[{"title":"Main"}]}"""),
        )
    }

    private fun jsonObject(raw: String): JsonObject {
        return JsonParser.parseString(raw).asJsonObject
    }

    private class FakeDeviceAdb(
        private val shellOutputs: Map<String, String>,
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            return shellOutputs[cmd].orEmpty()
        }

        override fun execAdbShellScript(cmd: String): String {
            return shellOutputs[cmd].orEmpty()
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class StubViewHierarchyClient(
        adb: IDeviceAdb,
        packageName: String,
        private val socketCandidates: List<String>? = null,
        private val responsesBySocket: Map<String, ViewHierarchyResponse?> = emptyMap(),
    ) : ViewHierarchyClient(adb, packageName) {
        val attemptedSockets = mutableListOf<String>()

        fun socketCandidates(): List<String> {
            return super.resolveSocketCandidates()
        }

        override fun resolveSocketCandidates(): List<String> {
            return socketCandidates ?: super.resolveSocketCandidates()
        }

        override fun sendRequestToSocket(
            socketName: String,
            request: ViewHierarchyRequest,
        ): ViewHierarchyResponse? {
            attemptedSockets.add(socketName)
            return responsesBySocket[socketName]
        }
    }
}
