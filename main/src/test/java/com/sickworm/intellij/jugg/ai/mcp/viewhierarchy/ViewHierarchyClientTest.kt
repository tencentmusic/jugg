package com.sickworm.intellij.jugg.ai.mcp.viewhierarchy

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

    @Test
    fun testVersionMismatchOnlyWarnsAndDoesNotBreakRequest() {
        val client = StubViewHierarchyClient(
            adb = FakeDeviceAdb(emptyMap()),
            packageName = "com.example.app",
            socketCandidates = listOf("jugg_vh"),
            responsesBySocket = mapOf(
                "jugg_vh" to okResponse(version = "0.9"),
            ),
        )

        val result = client.dumpLayout()

        Assert.assertNotNull(result)
        Assert.assertEquals(1, client.versionWarnings.size)
        Assert.assertEquals("0.9", client.versionWarnings[0])
    }

    @Test
    fun testVersionMatchedNoWarning() {
        val client = StubViewHierarchyClient(
            adb = FakeDeviceAdb(emptyMap()),
            packageName = "com.example.app",
            socketCandidates = listOf("jugg_vh"),
            responsesBySocket = mapOf(
                "jugg_vh" to okResponse(version = "1.1"),
            ),
        )

        val result = client.dumpLayout()

        Assert.assertNotNull(result)
        Assert.assertTrue(client.versionWarnings.isEmpty())
    }

    @Test
    fun testDumpLayoutKeepsServerErrorMessage() {
        val client = StubViewHierarchyClient(
            adb = FakeDeviceAdb(emptyMap()),
            packageName = "com.example.app",
            socketCandidates = listOf("jugg_vh"),
            responsesBySocket = mapOf(
                "jugg_vh" to ViewHierarchyResponse(
                    status = "error",
                    message = "本工程没有 kotlin 依赖，不支持此功能",
                    data = null,
                ),
            ),
        )

        val result = client.dumpLayout()

        Assert.assertEquals("本工程没有 kotlin 依赖，不支持此功能", result?.errorMessage)
    }

    @Test
    fun testFindElementsSendsSelectorBudgetAndParsesSourceLocation() {
        val client = StubViewHierarchyClient(
            adb = FakeDeviceAdb(emptyMap()),
            packageName = "com.example.app",
            socketCandidates = listOf("jugg_vh"),
            responsesBySocket = mapOf(
                "jugg_vh" to ViewHierarchyResponse(
                    status = "ok",
                    message = null,
                    data = jsonObject(
                        """{
                          "matchCount": 3,
                          "returnedCount": 1,
                          "truncated": true,
                          "density": 2.0,
                          "elements": [{
                            "text": "Profile",
                            "resourceId": "profile_card",
                            "contentDesc": "Open profile",
                            "className": "ProfileCard",
                            "bounds": [10, 20, 110, 60],
                            "centerX": 60,
                            "centerY": 40,
                            "source": {"file": "ProfileScreen.kt", "line": 42}
                          }]
                        }"""
                    ),
                ),
            ),
        )

        val result = client.findElements(
            text = "Profile",
            resourceId = "profile_card",
            contentDesc = "Open profile",
            className = "com.example.ProfileCard",
            visibleOnly = false,
            maxResults = 1,
        )

        Assert.assertEquals("find_elements", client.requests.single().action)
        Assert.assertEquals(
            mapOf(
                "text" to "Profile",
                "resourceId" to "profile_card",
                "contentDesc" to "Open profile",
                "className" to "com.example.ProfileCard",
                "visibleOnly" to false,
                "maxResults" to 1,
                "topWindowOnly" to true,
            ),
            client.requests.single().params,
        )
        Assert.assertEquals(3, result?.matchCount)
        Assert.assertEquals(1, result?.returnedCount)
        Assert.assertEquals(true, result?.truncated)
        Assert.assertEquals(2.0, result?.density ?: 0.0, 0.0)
        Assert.assertEquals("ProfileScreen.kt", result?.matches?.single()?.source?.file)
        Assert.assertEquals(42, result?.matches?.single()?.source?.line)
    }

    @Test
    fun testEvalViewParsesSourceLocation() {
        val client = StubViewHierarchyClient(
            adb = FakeDeviceAdb(emptyMap()),
            packageName = "com.example.app",
            socketCandidates = listOf("jugg_vh"),
            responsesBySocket = mapOf(
                "jugg_vh" to ViewHierarchyResponse(
                    status = "ok",
                    message = null,
                    data = jsonObject(
                        """{
                          "className": "android.widget.TextView",
                          "resourceId": "title",
                          "density": 3.0,
                          "source": {"file": "HomeScreen.kt", "line": 18},
                          "values": [{"expression": "getText()", "value": "Home", "type": "string"}]
                        }"""
                    ),
                ),
            ),
        )

        val result = client.evalView(null, "title", null, null, listOf("getText()"))

        Assert.assertEquals("HomeScreen.kt", result?.source?.file)
        Assert.assertEquals(18, result?.source?.line)
    }

    private fun okResponse(version: String = "1.0"): ViewHierarchyResponse {
        return ViewHierarchyResponse(
            status = "ok",
            message = null,
            data = jsonObject("""{"windows":[{"title":"Main"}]}"""),
            version = version,
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
        val requests = mutableListOf<ViewHierarchyRequest>()
        val versionWarnings = mutableListOf<String?>()

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
            requests.add(request)
            return responsesBySocket[socketName]
        }

        override fun onServerVersionMismatch(serverVersion: String?) {
            versionWarnings.add(serverVersion)
        }
    }
}
