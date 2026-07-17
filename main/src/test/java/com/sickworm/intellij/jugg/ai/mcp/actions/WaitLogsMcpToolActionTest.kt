package com.sickworm.intellij.jugg.ai.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.LastDeployTimestampRegistry
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

import com.sickworm.intellij.jugg.ai.mcp.McpToolResult

/** Test helper: cast McpToolResult.data to a typed map. */
@Suppress("UNCHECKED_CAST")
private fun McpToolResult.dataMap(): Map<String, Any?> = data as Map<String, Any?>

/**
 * WaitLogsMcpToolActionTest covers marker/crash/timeout stop conditions and edge cases.
 */
class WaitLogsMcpToolActionTest {

    private lateinit var projectDir: File
    private lateinit var registry: LastDeployTimestampRegistry
    private lateinit var adb: FakeStreamingAdb
    private lateinit var runtime: IMcpRuntime

    @Before
    fun setUp() {
        projectDir = createTempDir("wait_logs_test")
        registry = LastDeployTimestampRegistry()
        registry.setTimestamp(projectDir.absolutePath, "04-19 10:00:00.000")
        adb = FakeStreamingAdb()
        runtime = buildRuntime(adb, projectDir)
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    // --- marker stop ---

    @Test
    fun testStopsOnMarkerHit() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 I MyAutoRun: [JUGG_AR] START")
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 I MyAutoRun: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        val data = result.dataMap()
        assertEquals("marker", data["stopReason"])
        val logs = data["logs"] as String
        assertTrue(logs.contains("[JUGG_AR] DONE"))
    }

    @Test
    fun testMarkerFromOtherAppIgnored() {
        // PID 9999 does not belong to our package
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        adb.enqueueLine("04-19 10:00:01.000  9999  9999 I SomeTag: [JUGG_AR] DONE")
        // Our app's marker
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("marker", result.dataMap()["stopReason"])
        val logs = result.dataMap()["logs"] as String
        // Must contain our app's line
        assertTrue(logs.contains("1234"))
    }

    // --- crash stop ---

    @Test
    fun testStopsOnMainProcessCrash() {
        // Sequence:
        // 1. First crash-tagged weak signal arrives; pidof returns 1234 → mainProcessEverSeen = true
        // 2. Process dies; pidof returns empty
        // 3. Second crash line with strong signal arrives → stopReason = crash
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        // Weak crash line — process still alive on first pidof
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 E AndroidRuntime: Process: com.example.app, PID: 1234")
        // Now mark dead so subsequent pidof calls return empty
        adb.markMainProcessSeenThenDead = true
        // Strong crash line — this time pidof returns empty, triggering stop
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "NEVER_MATCH_THIS_MARKER_XYZ",
                "timeoutMs" to 5000,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("crash", result.dataMap()["stopReason"])
    }

    @Test
    fun testOtherAppCrashDoesNotStopWhenMainAlive() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        // Other app's crash; mainPids still has 1234
        adb.enqueueLine("04-19 10:00:01.000  9999  9999 E AndroidRuntime: FATAL EXCEPTION: main")
        // Our marker arrives next
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
                "timeoutMs" to 5000,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        // Must stop by marker, not by crash
        assertEquals("marker", result.dataMap()["stopReason"])
    }

    @Test
    fun testSystemCrashBeforeMainStartDoesNotStop() {
        // mainProcessEverSeen = false → ignore crash
        adb.packageName = "com.example.app"
        adb.mainPids = emptySet()
        adb.enqueueLine("04-19 10:00:00.500  9999  9999 E AndroidRuntime: FATAL EXCEPTION: main")
        // Then our app starts and marker hits
        adb.mainPids = setOf(1234)
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
                "timeoutMs" to 5000,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("marker", result.dataMap()["stopReason"])
    }

    @Test
    fun testChildProcessCrashIgnoredMainAlive() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        // Child process crash (pid 5678), main process (1234) still alive
        adb.enqueueLine("04-19 10:00:01.000  5678  5678 E AndroidRuntime: FATAL EXCEPTION: :push")
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
                "timeoutMs" to 5000,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("marker", result.dataMap()["stopReason"])
    }

    // --- timeout ---

    @Test
    fun testStopsOnTimeout() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        // No marker, no crash → timeout
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 I MyTag: line 1")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "NEVER_MATCH_XYZ",
                "timeoutMs" to 1000,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("timeout", result.dataMap()["stopReason"])
    }

    @Test
    fun testRealLogcatSourceCancelsStreamingOnTimeout() {
        val streamingAdb = FakeCancelableStreamingAdb()
        streamingAdb.packageName = "com.example.app"
        streamingAdb.mainPids = setOf(1234)
        val streamingRuntime = buildRuntime(streamingAdb, projectDir)

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "NEVER_MATCH_XYZ",
                "timeoutMs" to 1000,
            ),
            streamingRuntime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("timeout", result.dataMap()["stopReason"])
        assertTrue(streamingAdb.streamingCommand?.startsWith("logcat -T") == true)
        assertTrue("streaming adb should observe cancellation", streamingAdb.cancelObserved)
    }

    // --- param validation ---

    @Test
    fun testRejectMissingMarker() {
        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime,
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
    }

    @Test
    fun testRejectInvalidRegex() {
        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "[invalid(regex",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.INVALID_REGEX, result.errorCode)
    }

    @Test
    fun testRejectTimeoutTooSmall() {
        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "DONE",
                "timeoutMs" to 500,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
    }

    @Test
    fun testRejectTimeoutTooLarge() {
        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "DONE",
                "timeoutMs" to 400000,
            ),
            runtime,
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
    }

    // --- no deploy baseline ---

    @Test
    fun testNoDeployBaselineReturnsError() {
        val emptyRegistry = LastDeployTimestampRegistry()
        val action = WaitLogsMcpToolAction(emptyRegistry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.NO_DEPLOY_BASELINE, result.errorCode)
    }

    // --- multi-PID ---

    @Test
    fun testMultipleMainPidsAllNabbed() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234, 1235)
        adb.enqueueLine("04-19 10:00:01.000  1235  1235 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("marker", result.dataMap()["stopReason"])
    }

    // --- sub-process marker ---

    @Test
    fun testSubprocessMarkerStops() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        adb.subPids = setOf(5678) // :push process
        adb.enqueueLine("04-19 10:00:01.000  5678  5678 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("marker", result.dataMap()["stopReason"])
    }

    // --- tag filter ---

    @Test
    fun testTagFilterRetainsOnlyMatchingLogs() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 I WantedTag: line A")
        adb.enqueueLine("04-19 10:00:01.001  1234  1234 I UnwantedTag: line B")
        adb.enqueueLine("04-19 10:00:01.002  1234  1234 I WantedTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
                "tags" to listOf("WantedTag"),
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        val logs = result.dataMap()["logs"] as String
        assertTrue(logs.contains("line A"))
        assertFalse(logs.contains("line B"))
    }

    // --- truncation ---

    @Test
    fun testLogsOver100LinesTriggersTruncated() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        repeat(150) { i ->
            adb.enqueueLine("04-19 10:00:0${i / 100}.${i % 100}  1234  1234 I MyTag: log line $i")
        }
        adb.enqueueLine("04-19 10:00:02.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertTrue(result.dataMap()["truncated"] == true)
        val logs = result.dataMap()["logs"] as String
        val lineCount = logs.split("\n").count { it.isNotBlank() }
        assertTrue("logs must be at most 100 lines, got $lineCount", lineCount <= 100)
    }

    // --- ring buffer overflow ---

    @Test
    fun testRingBufferOverflowEvictsOldestLines() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        // Feed 10001 lines to exceed ring buffer
        repeat(10001) { i ->
            adb.enqueueLine("04-19 10:00:00.000  1234  1234 I MyTag: overflow line $i")
        }
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )
        // Should not OOM and should still return valid result
        assertNotNull(result)
        assertEquals("marker", result.dataMap()["stopReason"])
    }

    // --- allLogsPath artifact ---

    @Test
    fun testAllLogsPathArtifactPresent() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertNotNull(result.dataMap()["allLogsPath"])
        assertTrue(result.artifacts.any { it.type == "file" })
    }

    // --- targetPids in result ---

    @Test
    fun testTargetPidsReturnedInData() {
        adb.packageName = "com.example.app"
        adb.mainPids = setOf(1234)
        adb.enqueueLine("04-19 10:00:01.000  1234  1234 I MyTag: [JUGG_AR] DONE")

        val action = WaitLogsMcpToolAction(registry)
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "marker" to "\\[JUGG_AR\\] DONE",
            ),
            runtime,
        )

        val pids = result.dataMap()["targetPids"]
        assertNotNull(pids)
        assertTrue((pids as List<*>).contains(1234))
    }

    // --- helpers ---

    private fun buildRuntime(fakeAdb: IDeviceAdb, dir: File): IMcpRuntime {
        val device = Mockito.mock(IDevice::class.java)
        PlatformApi.impl = object : IPlatformApi {
            override fun showDialog(
                title: String,
                content: String,
                okButtonText: String?,
                cancelButtonText: String?,
                isShowCancelButton: Boolean,
            ) = false

            override fun showUserAndPasswordInputDialog(
                content: String,
                subTitle: String?,
                isPassword: Boolean,
                defaultInputText: String?,
                title: String?,
            ): String? = null

            override fun allAvailableJavaHomes() = emptyList<String>()
            override fun getGradleJdkPath(project: Project, logger: com.intellij.openapi.diagnostic.Logger) = null
            override fun getAndroidHomePath(logger: com.intellij.openapi.diagnostic.Logger) = null
            override fun getIdeVersion() = "test"
            override fun toDeviceAdb(device: IDevice): IDeviceAdb = fakeAdb
            override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: com.intellij.openapi.diagnostic.Logger) = false
            override fun invokeMcp(request: com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest) =
                throw UnsupportedOperationException()

            override fun getInitializedProjectDirs() = emptyList<File>()
            override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) =
                throw UnsupportedOperationException()
        }

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenAnswer { resolvePackageName(fakeAdb) }
        Mockito.`when`(deployTargetManager.getPackageNameOrNull()).thenAnswer { resolvePackageName(fakeAdb) }

        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(dir.absolutePath)

        return object : IMcpRuntime {
            override val logger = com.intellij.openapi.diagnostic.Logger.getInstance("WaitLogsTest")
            override val project: Project = project
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = FakeForceGradleCompileHelper()
            override val juggConfigurationRunner: IJuggConfigurationRunner = FakeJuggConfigurationRunner()
        }
    }

    private fun resolvePackageName(adb: IDeviceAdb): String {
        return when (adb) {
            is FakeStreamingAdb -> adb.packageName
            is FakeCancelableStreamingAdb -> adb.packageName
            else -> "com.example.app"
        }
    }

    /**
     * FakeStreamingAdb simulates `adb logcat` line-by-line output and pidof/ps responses.
     *
     * Implements [LogcatSourceProvider] so WaitLogsMcpToolAction uses the enqueued lines
     * instead of spawning a real adb subprocess.
     */
    class FakeStreamingAdb : IDeviceAdb, LogcatSourceProvider {
        var packageName: String = "com.example.app"
        var mainPids: Set<Int> = emptySet()
        var subPids: Set<Int> = emptySet()
        /** When true, the first pidof call returns mainPids (so mainProcessEverSeen = true),
         *  then subsequent calls return empty (process dead). */
        var markMainProcessSeenThenDead: Boolean = false
        private var pidofCallCount = 0

        private val logLines = java.util.concurrent.LinkedBlockingQueue<String>()

        fun enqueueLine(line: String) {
            logLines.offer(line)
        }

        override fun createLogcatSource(sinceTime: String): AdbLogcatSource {
            return object : AdbLogcatSource {
                override fun nextLine(pollTimeoutMs: Long): String? =
                    logLines.poll(pollTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

                override fun close() {}
            }
        }

        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            return when {
                cmd.startsWith("pidof") && !cmd.contains(":") -> {
                    if (markMainProcessSeenThenDead) {
                        val count = ++pidofCallCount
                        // First call returns PIDs so mainProcessEverSeen = true; then returns empty
                        if (count <= 1) mainPids.joinToString(" ") else ""
                    } else {
                        mainPids.joinToString(" ")
                    }
                }
                cmd.contains("ps") && cmd.contains(packageName) -> {
                    (subPids + mainPids).joinToString("\n") { pid ->
                        "u0_a123    $pid  1234 1234 S $packageName"
                    }
                }
                else -> ""
            }
        }

        override fun push(from: File, to: String) = true
        override fun pull(from: String, to: File) = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String) = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    class FakeCancelableStreamingAdb : IDeviceAdb {
        var packageName: String = "com.example.app"
        var mainPids: Set<Int> = emptySet()
        var streamingCommand: String? = null
            private set
        @Volatile var cancelObserved: Boolean = false
            private set

        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            return when {
                cmd.startsWith("pidof") -> mainPids.joinToString(" ")
                cmd.contains("ps") && cmd.contains(packageName) -> {
                    mainPids.joinToString("\n") { pid ->
                        "u0_a123    $pid  1234 1234 S $packageName"
                    }
                }
                else -> ""
            }
        }

        override fun execAdbShellCmdStreaming(
            cmd: String,
            lineConsumer: (String) -> Unit,
            cancelSignal: () -> Boolean,
        ): Int {
            streamingCommand = cmd
            try {
                while (!cancelSignal()) {
                    Thread.sleep(20)
                }
            } catch (_: InterruptedException) {
                // Treat interruption after close() as observing the cancellation path.
            }
            cancelObserved = cancelSignal()
            return -1
        }

        override fun push(from: File, to: String) = true
        override fun pull(from: String, to: File) = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String) = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
