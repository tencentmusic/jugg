package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
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
            val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java), Mockito.mock(NativeLibraryDelta::class.java))
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
            val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java), Mockito.mock(NativeLibraryDelta::class.java))

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
                val writer = NativeSandboxWriter(RecordingAdb(), sandbox, Mockito.mock(Logger::class.java), Mockito.mock(NativeLibraryDelta::class.java))
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
            val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java), Mockito.mock(NativeLibraryDelta::class.java))

            assertFailsWith<IllegalArgumentException> {
                writer.stage(request(item).copy(apkNamesByPath = emptyMap()))
            }
            assertTrue(adb.pushedFiles.isEmpty())
        }
    }

    @Test
    fun `run as sends only delta using a patcher inside the app sandbox`() {
        withDelta { writer, request, adb, _, patch ->
            writer.stage(request)
            assertEquals(listOf(patch), adb.pushedFiles)
        }
    }

    @Test
    fun `different device baseline sends full source without sending delta`() {
        withDelta(deviceHash = "different") { writer, request, adb, source, _ ->
            writer.stage(request)
            assertEquals(listOf(source), adb.pushedFiles)
        }
    }

    @Test
    fun `patch content mismatch falls back once to full source`() {
        withDelta(resultHash = "corrupt") { writer, request, adb, source, patch ->
            writer.stage(request)
            assertEquals(listOf(patch, source), adb.pushedFiles)
        }
    }

    @Test
    fun `device out of space fails without sending full source`() {
        withDelta(patchStatus = 24) { writer, request, adb, _, patch ->
            val failure = assertFailsWith<NativeSandboxDeployException> { writer.stage(request) }
            assertEquals(NativeSandboxDeployStep.COPY, failure.step)
            assertEquals(listOf(patch), adb.pushedFiles)
        }
    }

    @Test
    fun `failed diff generation falls back to full source`() {
        withDelta(generationFailure = true) { writer, request, adb, source, _ ->
            writer.stage(request)
            assertEquals(listOf(source), adb.pushedFiles)
        }
    }

    @Test
    fun `private patcher execution denied falls back to full source`() {
        withDelta(patcherAvailable = false) { writer, request, adb, source, _ ->
            writer.stage(request)
            assertEquals(listOf(source), adb.pushedFiles)
        }
    }

    @Test
    fun `patcher staging failure does not send a full source`() {
        withDelta(patcherPrepared = false) { writer, request, adb, _, _ ->
            val failure = assertFailsWith<NativeSandboxDeployException> { writer.stage(request) }
            assertEquals(NativeSandboxDeployStep.COPY, failure.step)
            assertTrue(adb.pushedFiles.isEmpty())
        }
    }

    private fun withDelta(deviceHash: String = "old", resultHash: String = "new",
        patchStatus: Int = 0, generationFailure: Boolean = false, patcherAvailable: Boolean = true,
        patcherPrepared: Boolean = true,
        block: (NativeSandboxWriter, NativeSandboxWriteRequest, RecordingAdb, File, File) -> Unit) {
        withLargeNative { item, source ->
            val root = Files.createTempDirectory("jugg-native-delta-writer").toFile()
            try {
                val baseline = File(root, "old.so").apply { writeText("old") }
                val patch = File(root, "new.patch").apply { writeText("patch") }
                val delta = Mockito.mock(NativeLibraryDelta::class.java)
                whenever(delta.deviceTool("arm64-v8a")).thenReturn(patch)
                if (generationFailure) {
                    whenever(delta.createPatch(any(), any(), any())).thenAnswer { throw java.io.IOException("diff failed") }
                } else {
                    whenever(delta.createPatch(any(), any(), any()))
                        .thenReturn(NativeLibraryDelta.Patch(patch, "old", "new"))
                }
                val adb = RecordingAdb(cachedPatcher = true)
                val sandbox = deltaSandbox(deviceHash, resultHash, patchStatus, patcherAvailable, patcherPrepared)
                val writer = NativeSandboxWriter(adb, sandbox, Mockito.mock(Logger::class.java),
                    delta, CompileUiHandler.DEFAULT)
                whenever(delta.baseline(item.apkPath, item.name)).thenReturn(baseline)
                val request = request(item)
                block(writer, request, adb, source, patch)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun deltaSandbox(deviceHash: String, resultHash: String, patchStatus: Int,
        patcherAvailable: Boolean, patcherPrepared: Boolean): AppSandboxExecutor {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        whenever(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.RUN_AS)
        whenever(sandbox.execNoFallback(any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(0)
            val privateTool = "${NativeSandboxWriter.TEMP_ROOT}/session/hpatchz"
            when {
                command.contains("STAGED_TOOL") -> if (patcherPrepared) {
                    "__JUGG_NATIVE_DELTA__ STAGED_TOOL"
                } else {
                    "cp: No space left on device"
                }
                command.contains(" -v") -> if (patcherAvailable && command.contains("$privateTool -v")) {
                    "__JUGG_NATIVE_DELTA__ TOOL"
                } else {
                    "sh: hpatchz: can't execute: Permission denied"
                }
                command.startsWith("hash=") -> "__JUGG_NATIVE_DELTA__ HASH $deviceHash"
                command.contains(" -s-8m ") && !command.contains("$privateTool -s-8m ") ->
                    "__JUGG_NATIVE_DELTA__ STATUS 126"
                command.contains("result=") -> "__JUGG_NATIVE_DELTA__ STATUS $patchStatus\n" +
                    "__JUGG_NATIVE_DELTA__ HASH $resultHash"
                else -> "__JUGG_NATIVE_DELTA__ BASE\n__JUGG_NATIVE_DELTA__ TOOL\n" +
                    "__JUGG_NATIVE_DELTA__ CLEAN\n${NativeSandboxWriter.MARKER} OK"
            }
        }
        return sandbox
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

    private class RecordingAdb(private val cachedPatcher: Boolean = false) : IDeviceAdb {
        val pushedFiles = mutableListOf<File>()
        override val displayName = "fake"
        override val api = 35
        override val serial = "serial"
        override val isOnline = true
        override fun execAdbShellCmd(cmd: String) = if (cachedPatcher) "__JUGG_NATIVE_DELTA__ TOOL" else ""
        override fun execAdbShellScript(cmd: String) = cmd
        override fun push(from: File, to: String): Boolean { pushedFiles += from; return true }
        override fun pull(from: String, to: File) = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String) = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = if (cachedPatcher) "arm64-v8a" else null
    }
}
