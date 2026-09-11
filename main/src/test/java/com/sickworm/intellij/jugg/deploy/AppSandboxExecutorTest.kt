package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

class AppSandboxExecutorTest {

    @Test
    fun `run-as marker with accepted uid should keep Apply Changes compatible`() {
        val adb = FakeAdb(runAsOutput = compatibleRunAsOutput(10001))
        val executor = executor(adb)

        executor.exec("ls code_cache")

        assertEquals(AppSandboxExecutor.ApplyChangesCapability.COMPATIBLE, executor.applyChangesCapability)
        assertEquals(AppSandboxExecutor.Mode.RUN_AS, executor.mode)
        assertTrue(adb.scripts.last().startsWith("run-as com.example.app sh -c"))
    }

    @Test
    fun `run-as files with mismatched SELinux context should use direct sandbox`() {
        val adb = FakeAdb(
            runAsOutput = "__JUGG_RUN_AS_OK__:10001\n" +
                "__JUGG_RUN_AS_CONTEXT__:u:object_r:app_data_file:s0:c512,c768|" +
                "u:object_r:app_data_file:s0:c148,c256,c512,c768",
            directProbeOutputs = listOf("__JUGG_DIRECT_SANDBOX_OK__"),
        )
        val executor = executor(adb)

        assertEquals(AppSandboxExecutor.ApplyChangesCapability.INCOMPATIBLE, executor.applyChangesCapability)
        assertEquals(AppSandboxExecutor.Mode.DIRECT_SHELL, executor.mode)
    }

    @Test
    fun `uid outside Apply Changes range should use ordinary shell when full probe succeeds`() {
        val adb = FakeAdb(
            runAsOutput = compatibleRunAsOutput(1000),
            directProbeOutputs = listOf("__JUGG_DIRECT_SANDBOX_OK__"),
            dataDir = "/data/user_de/0/com.example.app",
        )
        val executor = executor(adb)

        executor.exec("mkdir -p code_cache/test", repairCodeCache = true)

        assertEquals(AppSandboxExecutor.ApplyChangesCapability.INCOMPATIBLE, executor.applyChangesCapability)
        assertEquals(AppSandboxExecutor.Mode.DIRECT_SHELL, executor.mode)
        assertEquals(0, adb.rootRequestCount)
        assertEquals("/data/user_de/0/com.example.app/code_cache/test", executor.absolutePath("code_cache/test"))
        assertTrue(adb.scripts.last().contains("trap repair_code_cache EXIT"))
    }

    @Test
    fun `capability check should not request root before Direct Deploy starts`() {
        val adb = FakeAdb(
            runAsOutput = "permission denied",
            shellUid = "2000",
            directProbeOutputs = listOf("permission denied"),
            rootReconnect = true,
        )
        val executor = executor(adb)

        assertEquals(AppSandboxExecutor.ApplyChangesCapability.INCOMPATIBLE, executor.applyChangesCapability)
        assertEquals(0, adb.rootRequestCount)
    }

    @Test
    fun `failed ordinary shell probe should request adb root once and probe again`() {
        val adb = FakeAdb(
            runAsOutput = "run-as: couldn't stat /data/user/0/com.example.app: Permission denied",
            shellUid = "2000",
            directProbeOutputs = listOf("permission denied", "__JUGG_DIRECT_SANDBOX_OK__"),
            rootReconnect = true,
        )
        val executor = executor(adb)

        assertEquals(AppSandboxExecutor.Mode.ROOT_DIRECT, executor.mode)
        assertEquals(1, adb.rootRequestCount)
        assertEquals(2, adb.directProbeCount)
    }

    @Test
    fun `su mode should wrap every privileged script with verified command`() {
        val adb = FakeAdb(
            runAsOutput = "permission denied",
            shellUid = "2000",
            directProbeOutputs = listOf("permission denied"),
            rootReconnect = false,
            suUidOutput = "__JUGG_DIRECT_SANDBOX_OK__",
        )
        val executor = executor(adb)

        executor.exec("ls code_cache")

        assertEquals(AppSandboxExecutor.Mode.SU_ROOT, executor.mode)
        assertEquals(1, adb.rootRequestCount)
        assertTrue(adb.scripts.last().startsWith("su 0 sh -c"))
    }

    @Test
    fun `su fallback should use the verified default form`() {
        val adb = FakeAdb(
            runAsOutput = "permission denied",
            shellUid = "0",
            directProbeOutputs = listOf("permission denied"),
            suDefaultOutput = "__JUGG_DIRECT_SANDBOX_OK__",
        )
        val executor = executor(adb)

        executor.exec("ls code_cache")

        assertEquals(AppSandboxExecutor.Mode.SU_ROOT, executor.mode)
        assertEquals(0, adb.rootRequestCount)
        assertTrue(adb.scripts.last().startsWith("su -c "))
    }

    @Test
    fun `su fallback should support command-only form`() {
        val adb = FakeAdb(
            runAsOutput = "permission denied",
            shellUid = "0",
            directProbeOutputs = listOf("permission denied"),
            suCommandOutput = "__JUGG_DIRECT_SANDBOX_OK__",
        )
        val executor = executor(adb)

        executor.exec("ls code_cache")

        assertEquals(AppSandboxExecutor.Mode.SU_ROOT, executor.mode)
        assertEquals(0, adb.rootRequestCount)
        assertTrue(adb.scripts.last().startsWith("su sh -c "))
    }

    @Test
    fun `all direct modes unavailable should preserve diagnostic reason`() {
        val adb = FakeAdb(
            runAsOutput = "permission denied",
            shellUid = "2000",
            directProbeOutputs = listOf("permission denied"),
            rootReconnect = false,
        )
        val executor = executor(adb)

        assertEquals(AppSandboxExecutor.Mode.UNAVAILABLE, executor.mode)
        assertEquals(1, adb.rootRequestCount)
        assertTrue(executor.unavailableReason.orEmpty().contains("root requested=true"))
        assertTrue(executor.unavailableReason.orEmpty().contains("su unavailable"))
    }

    @Test(expected = IOException::class)
    fun `adb failure during run-as probe should propagate`() {
        val adb = FakeAdb(runAsException = IOException("device offline"))

        executor(adb).mode
    }

    @Test(expected = IOException::class)
    fun `adb failure during root request should propagate`() {
        val adb = FakeAdb(
            runAsOutput = "permission denied",
            shellUid = "2000",
            directProbeOutputs = listOf("permission denied"),
            rootException = IOException("device offline"),
        )

        executor(adb).mode
    }

    @Test(expected = IllegalStateException::class)
    fun `direct command should fail when code cache repair does not finish`() {
        val adb = FakeAdb(
            runAsOutput = compatibleRunAsOutput(1000),
            directProbeOutputs = listOf("__JUGG_DIRECT_SANDBOX_OK__"),
            commandOutput = "command completed",
        )

        executor(adb).exec("mkdir -p code_cache/test", repairCodeCache = true)
    }

    @Test
    fun `code cache repair output should not pollute command result`() {
        val adb = FakeAdb(
            runAsOutput = compatibleRunAsOutput(1000),
            directProbeOutputs = listOf("__JUGG_DIRECT_SANDBOX_OK__"),
            commandOutput = "success\n" +
                "__JUGG_APP_SANDBOX_REPAIR_START__\n" +
                "SELinux: Loaded file context from:\n" +
                "/system/etc/selinux/plat_file_contexts\n" +
                "__JUGG_APP_SANDBOX_REPAIRED__",
        )

        val result = executor(adb).exec("echo success", repairCodeCache = true)

        assertEquals("success", result)
        assertTrue(adb.scripts.last().contains("__JUGG_APP_SANDBOX_REPAIR_START__"))
        assertTrue(adb.scripts.last().contains("u:object_r:apk_data_file:s0"))
    }

    private fun executor(adb: IDeviceAdb): AppSandboxExecutor {
        return AppSandboxExecutor(adb, "com.example.app", Mockito.mock(Logger::class.java))
    }

    private class FakeAdb(
        private val runAsOutput: String = compatibleRunAsOutput(10001),
        private val runAsException: Exception? = null,
        private val shellUid: String = "0",
        private val dataDir: String = "/data/user/0/com.example.app",
        directProbeOutputs: List<String> = emptyList(),
        private val rootReconnect: Boolean = false,
        private val rootException: Exception? = null,
        private val suUidOutput: String = "",
        private val suDefaultOutput: String = "",
        private val suCommandOutput: String = "",
        private val commandOutput: String? = null,
    ) : IDeviceAdb {
        val commands = mutableListOf<String>()
        val scripts = mutableListOf<String>()
        var rootRequestCount = 0
            private set
        var directProbeCount = 0
            private set
        private val directProbeOutputs = ArrayDeque(directProbeOutputs)

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            commands += cmd
            return when {
                cmd == "id -u" -> shellUid
                cmd.startsWith("dumpsys package ") -> "  dataDir=$dataDir"
                else -> ""
            }
        }

        override fun execAdbShellScript(cmd: String): String {
            scripts += cmd
            return when {
                cmd.contains("__JUGG_RUN_AS_OK__") -> {
                    runAsException?.let { throw it }
                    runAsOutput
                }
                cmd.startsWith("su 0 ") && cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> suUidOutput
                cmd.startsWith("su -c ") && cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> suDefaultOutput
                cmd.startsWith("su sh -c ") && cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> suCommandOutput
                cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> {
                    directProbeCount++
                    if (directProbeOutputs.isEmpty()) "" else directProbeOutputs.removeFirst()
                }
                else -> commandOutput ?: if (cmd.contains("__JUGG_APP_SANDBOX_REPAIRED__")) {
                    "__JUGG_APP_SANDBOX_REPAIR_START__\n__JUGG_APP_SANDBOX_REPAIRED__"
                } else {
                    ""
                }
            }
        }

        override fun requestRootAdbd(): Boolean {
            rootRequestCount++
            rootException?.let { throw it }
            return rootReconnect
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    companion object {
        private fun compatibleRunAsOutput(uid: Int): String {
            val context = "u:object_r:app_data_file:s0:c123,c256,c512,c768"
            return "__JUGG_RUN_AS_OK__:$uid\n__JUGG_RUN_AS_CONTEXT__:$context|$context"
        }
    }
}
