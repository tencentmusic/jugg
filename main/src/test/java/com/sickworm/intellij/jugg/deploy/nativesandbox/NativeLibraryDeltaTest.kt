package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLibraryDeltaTest {
    @Test
    fun `streaming patch reconstructs changed binary and records both content identities`() {
        val dir = Files.createTempDirectory("jugg-native-delta").toFile()
        try {
            val old = File(dir, "lib/arm64-v8a/libold.so").apply {
                parentFile.mkdirs()
                writeBytes(Random(42).nextBytes(1024 * 1024))
            }
            val content = old.readBytes().apply { this[321] = (this[321] + 1).toByte() }
            val new = File(dir, "new.so").apply { writeBytes(content) }
            val history = Mockito.mock(IDeployHistoryManager::class.java)
            val splitRoot = File(dir, "split")
            val splitOld = File(splitRoot, "lib/arm64-v8a/libold.so").apply {
                parentFile.mkdirs()
                writeText("other APK")
            }
            whenever(history.getDeployedData()).thenReturn(listOf(
                CompileOutput(CompileOutput.Type.NativeLib, old, dir, "/base.apk"),
                CompileOutput(CompileOutput.Type.NativeLib, splitOld, splitRoot, "/split.apk"),
                CompileOutput(CompileOutput.Type.Asset, splitOld, splitRoot, "/base.apk"),
            ))
            val delta = NativeLibraryDelta(CompileUiHandler.DEFAULT, Mockito.mock(Logger::class.java), history)

            val baseline = assertNotNull(delta.baseline("/base.apk", "lib/arm64-v8a/libold.so"))
            val patch = assertNotNull(delta.createPatch(baseline, new, dir))
            val restored = File(dir, "restored.so")
            val tool = assertNotNull(delta.hostTool("hpatchz"))
            val process = ProcessBuilder(tool.path, "-s", old.path, patch.file.path, restored.path)
                .redirectErrorStream(true).redirectOutput(File(dir, "patch.log")).start()

            assertEquals(0, process.waitFor())
            assertTrue(patch.file.length() < new.length() / 10)
            assertContentEquals(content, restored.readBytes())
            assertEquals(delta.sha256(old), patch.oldSha256)
            assertEquals(delta.sha256(restored), patch.newSha256)
            assertEquals(splitOld, delta.baseline("/split.apk", "lib/arm64-v8a/libold.so"))
            assertNull(delta.baseline("/missing.apk", "lib/arm64-v8a/libold.so"))
            splitOld.delete()
            assertNull(delta.baseline("/split.apk", "lib/arm64-v8a/libold.so"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancelled diff does not start or produce a patch`() {
        val ui = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isCanceled = true
        }
        val dir = Files.createTempDirectory("jugg-native-delta-cancel").toFile()
        try {
            val old = File(dir, "old.so").apply { writeText("old") }
            val new = File(dir, "new.so").apply { writeText("new") }
            val delta = NativeLibraryDelta(ui, Mockito.mock(Logger::class.java), Mockito.mock(IDeployHistoryManager::class.java))
            assertFailsWith<java.util.concurrent.CancellationException> { delta.createPatch(old, new, dir) }
            assertTrue(dir.listFiles().orEmpty().none { it.extension == "patch" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
