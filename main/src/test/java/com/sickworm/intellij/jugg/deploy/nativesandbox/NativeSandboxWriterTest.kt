package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.assertFailsWith

class NativeSandboxWriterTest {
    @Test
    fun `file backed native library stages source and publishes into apk scoped overlay`() {
        withLargeNative { item, source ->
            val adb = RecordingAdb()
            val (sandbox, scripts) = recordingSandbox()
            val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))
            val request = request(item)

            writer.stage(request)
            writer.publish(request)
            writer.discard(request)

            assertEquals(listOf(source), adb.pushedFiles)
            assertTrue(scripts.any { it.contains("cp -f ${NativeSandboxWriter.STAGING_ROOT}/session/base.apk/lib/arm64-v8a/liblarge.so") })
            assertTrue(scripts.any { it.contains("mv code_cache/.jugg_native_stage/session/pending/base.apk/lib/arm64-v8a/liblarge.so code_cache/.overlay/base.apk/lib/arm64-v8a/liblarge.so") })
            assertFalse(scripts.any { it.contains("touch code_cache/.jugg_native/.enabled") })
        }
    }

    @Test
    fun `failed source validation never publishes the native overlay`() {
        withLargeNative { item, source ->
            source.delete()
            val adb = RecordingAdb()
            val (sandbox, scripts) = recordingSandbox()
            val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

            val failure = assertFailsWith<NativeSandboxDeployException> { writer.stage(request(item)) }

            assertEquals(NativeSandboxDeployStep.PUSH, failure.step)
            assertTrue(adb.pushedFiles.isEmpty())
            assertFalse(scripts.any { it.contains("mv code_cache/.jugg_native_stage") })
        }
    }

    @Test
    fun `partial publish rollback restores old libraries and leaves untouched libraries intact`() {
        withLargeNative { first, source ->
            val second = DeployItem.fileBackedNativeLib(
                name = "lib/arm64-v8a/libsecond.so", checksum = 2L,
                file = source, apkPath = "/tmp/base.apk",
            )
            val root = Files.createTempDirectory("jugg-native-publish").toFile()
            try {
                val oldFirst = File(root, "code_cache/.overlay/base.apk/lib/arm64-v8a/liblarge.so")
                val oldSecond = File(root, "code_cache/.overlay/base.apk/lib/arm64-v8a/libsecond.so")
                val pendingFirst = File(root,
                    "code_cache/.jugg_native_stage/session/pending/base.apk/lib/arm64-v8a/liblarge.so")
                oldFirst.parentFile.mkdirs()
                pendingFirst.parentFile.mkdirs()
                oldFirst.writeText("old-first")
                oldSecond.writeText("old-second")
                pendingFirst.writeText("new-first")
                val sandbox = shellSandbox(root)
                val writer = NativeSandboxWriter(RecordingAdb(), sandbox, Mockito.mock(Logger::class.java))
                val request = request(first).copy(files = listOf(first, second))

                assertFailsWith<NativeSandboxDeployException> { writer.publish(request) }
                assertEquals("new-first", oldFirst.readText())
                writer.rollback(request)

                assertEquals("old-first", oldFirst.readText())
                assertEquals("old-second", oldSecond.readText())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `unknown apk scope fails before pushing`() {
        withLargeNative { item, _ ->
            val adb = RecordingAdb()
            val (sandbox, _) = recordingSandbox()
            val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java))

            assertFailsWith<IllegalArgumentException> {
                writer.stage(request(item).copy(apkNamesByPath = emptyMap()))
            }
            assertTrue(adb.pushedFiles.isEmpty())
        }
    }

    private fun withLargeNative(block: (DeployItem, File) -> Unit) {
        Assume.assumeFalse("sparse files over 2 GiB are not portable", isWindows)
        val source = Files.createTempFile("jugg-large-native", ".so").toFile()
        try {
            RandomAccessFile(source, "rw").use { it.setLength(Int.MAX_VALUE + 1L) }
            val item = DeployItem.fileBackedNativeLib(
                name = "lib/arm64-v8a/liblarge.so", checksum = 1L,
                file = source, apkPath = "/tmp/base.apk",
            )
            block(item, source)
        } finally {
            source.delete()
        }
    }

    private fun request(item: DeployItem) = NativeSandboxWriteRequest(
        packageName = "com.example.app", sessionId = "session",
        files = listOf(item), apkNamesByPath = mapOf("/tmp/base.apk" to "base.apk"),
    )

    private fun recordingSandbox(): Pair<AppSandboxExecutor, MutableList<String>> {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        val scripts = mutableListOf<String>()
        whenever(sandbox.execNoFallback(any(), any())).thenAnswer { invocation ->
            scripts += invocation.getArgument<String>(0)
            "${NativeSandboxWriter.MARKER} OK"
        }
        return sandbox to scripts
    }

    private fun shellSandbox(root: File): AppSandboxExecutor {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        whenever(sandbox.execNoFallback(any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(0)
            val process = ProcessBuilder("sh", "-c", command)
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw IllegalStateException(output)
            output
        }
        return sandbox
    }

    private class RecordingAdb : IDeviceAdb {
        val pushedFiles = mutableListOf<File>()
        override val displayName = "fake"
        override val api = 35
        override val serial = "serial"
        override val isOnline = true
        override fun execAdbShellCmd(cmd: String) = ""
        override fun execAdbShellScript(cmd: String) = cmd
        override fun push(from: File, to: String): Boolean { pushedFiles += from; return true }
        override fun pull(from: String, to: File) = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String) = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
