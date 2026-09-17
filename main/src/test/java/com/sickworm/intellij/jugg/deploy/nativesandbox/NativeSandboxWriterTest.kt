package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.io.File

class NativeSandboxWriterTest {

    @Test
    fun `write pushes staging files and copies them through sandbox`() {
        val adb = RecordingAdb(copyOutput = "${NativeSandboxWriter.MARKER} OK")
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        whenever(sandbox.execNoFallback(any(), eq(true))).thenAnswer { invocation ->
            adb.lastCopyScript = invocation.getArgument(0)
            "${NativeSandboxWriter.MARKER} OK"
        }
        val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

        writer.write(
            NativeSandboxWriteRequest(
                packageName = "com.example.app",
                sessionId = "session-1",
                abiDirs = mapOf("arm64-v8a" to listOf(nativeLib("lib/arm64-v8a/libdtmp.so", byteArrayOf(1, 2, 3)))),
            ),
        )

        assertEquals(
            listOf("${NativeSandboxWriter.STAGING_ROOT}/session-1/arm64-v8a/libdtmp.so"),
            adb.pushedPaths,
        )
        assertTrue(adb.commands.any { it.contains("mkdir -p ${NativeSandboxWriter.STAGING_ROOT}/session-1/arm64-v8a") })
        assertTrue(adb.lastCopyScript.contains("cp -f ${NativeSandboxWriter.STAGING_ROOT}/session-1/arm64-v8a/libdtmp.so"))
        assertTrue(adb.lastCopyScript.contains("code_cache/.jugg_native/arm64-v8a/libdtmp.so"))
        assertTrue(adb.lastCopyScript.contains("touch code_cache/.jugg_native/.enabled"))
        assertTrue(adb.lastCopyScript.contains("[ \"\$actual\" -eq 3 ]"))
        assertTrue(adb.commands.any { it == "rm -rf ${NativeSandboxWriter.STAGING_ROOT}/session-1" })
        Mockito.verify(sandbox).execNoFallback(any(), eq(true))
    }

    @Test
    fun `push failure is not reported as success`() {
        val adb = RecordingAdb(pushSuccess = false)
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

        try {
            writer.write(
                NativeSandboxWriteRequest(
                    packageName = "com.example.app",
                    sessionId = "session-2",
                    abiDirs = mapOf("arm64-v8a" to listOf(nativeLib("lib/arm64-v8a/libdtmp.so"))),
                ),
            )
            fail("expected NativeSandboxDeployException")
        } catch (e: NativeSandboxDeployException) {
            assertEquals(NativeSandboxDeployStep.PUSH, e.step)
        }
        Mockito.verify(sandbox, Mockito.never()).execNoFallback(any(), any())
        assertTrue(adb.commands.any { it == "rm -rf ${NativeSandboxWriter.STAGING_ROOT}/session-2" })
    }

    @Test
    fun `missing success marker is a copy failure`() {
        val adb = RecordingAdb()
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        whenever(sandbox.execNoFallback(any(), eq(true))).thenReturn("permission denied")
        val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

        try {
            writer.write(
                NativeSandboxWriteRequest(
                    packageName = "com.example.app",
                    sessionId = "session-3",
                    abiDirs = mapOf("arm64-v8a" to listOf(nativeLib("lib/arm64-v8a/libdtmp.so"))),
                ),
            )
            fail("expected NativeSandboxDeployException")
        } catch (e: NativeSandboxDeployException) {
            assertEquals(NativeSandboxDeployStep.COPY, e.step)
        }
    }

    @Test
    fun `unsafe so path is rejected`() {
        val adb = RecordingAdb()
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

        try {
            writer.write(
                NativeSandboxWriteRequest(
                    packageName = "com.example.app",
                    sessionId = "session-4",
                    abiDirs = mapOf("arm64-v8a" to listOf(nativeLib("lib/arm64-v8a/../libdtmp.so"))),
                ),
            )
            fail("expected NativeSandboxDeployException")
        } catch (e: NativeSandboxDeployException) {
            assertEquals(NativeSandboxDeployStep.PUSH, e.step)
        }
        assertTrue(adb.pushedPaths.isEmpty())
        assertFalse(adb.lastCopyScript.contains("cp -f"))
    }

    @Test
    fun `fallback cleanup removes this round files not the abi directory`() {
        val adb = RecordingAdb()
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        var lastCleanup = ""
        whenever(sandbox.exec(any(), any())).thenAnswer { invocation ->
            lastCleanup = invocation.getArgument(0)
            "success"
        }
        val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

        writer.bestEffortRemovePatchFiles(
            mapOf(
                "arm64-v8a" to listOf(
                    nativeLib("lib/arm64-v8a/libdtmp.so"),
                    nativeLib("lib/arm64-v8a/libother.so"),
                ),
            ),
        )

        assertTrue(lastCleanup.contains("rm -f code_cache/.jugg_native/arm64-v8a/libdtmp.so"))
        assertTrue(lastCleanup.contains("rm -f code_cache/.jugg_native/arm64-v8a/libother.so"))
        assertFalse(lastCleanup.contains("rm -rf"))
    }

    @Test
    fun `set enabled writes or removes the runtime flag without deleting patches`() {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        val commands = mutableListOf<String>()
        whenever(sandbox.exec(any(), any())).thenAnswer { invocation ->
            commands += invocation.getArgument<String>(0)
            "success"
        }
        val writer = NativeSandboxWriter(RecordingAdb(), sandbox, Mockito.mock(Logger::class.java))

        assertTrue(writer.bestEffortSetEnabled(true))
        assertTrue(writer.bestEffortSetEnabled(false))
        assertEquals(
            "mkdir -p code_cache/.jugg_native && touch code_cache/.jugg_native/.enabled && echo success",
            commands[0],
        )
        assertEquals(
            "rm -f code_cache/.jugg_native/.enabled && echo success",
            commands[1],
        )
        assertFalse(commands.any { it.contains("rm -rf") })
    }

    private fun nativeLib(name: String, content: ByteArray = byteArrayOf(1)): DeployItem {
        return DeployItem(
            name = name,
            type = CompileOutput.Type.NativeLib,
            checksum = 1L,
            content = content,
            apkPath = "/tmp/app.apk",
        )
    }

    private class RecordingAdb(
        private val copyOutput: String = "",
        private val pushSuccess: Boolean = true,
    ) : IDeviceAdb {
        val commands = mutableListOf<String>()
        val pushedPaths = mutableListOf<String>()
        var lastCopyScript: String = ""

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            commands += cmd
            return ""
        }

        override fun execAdbShellScript(cmd: String): String = cmd
        override fun push(from: File, to: String): Boolean {
            pushedPaths += to
            return pushSuccess
        }
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
