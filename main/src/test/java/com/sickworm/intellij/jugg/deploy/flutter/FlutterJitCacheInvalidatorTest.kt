package com.sickworm.intellij.jugg.deploy.flutter

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import kotlin.test.assertFailsWith

class FlutterJitCacheInvalidatorTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `removes flutter timestamp and keeps extracted runtime files`() {
        val appFlutter = appFlutterDir()
        val timestamp = File(appFlutter, "res_timestamp-1-1789261483352").apply { writeText("1") }
        val kernel = File(appFlutter, "flutter_assets/kernel_blob.bin").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        invalidator(sandboxExecutingIn(temporaryFolder.root)).invalidate()

        assertFalse(timestamp.exists())
        assertTrue(kernel.isFile)
    }

    @Test
    fun `missing timestamp is an idempotent success and keeps other files`() {
        val appFlutter = appFlutterDir()
        // Same prefix family, but not a Flutter timestamp file.
        val backup = File(appFlutter, "res_timestamp_backup").apply { writeText("keep") }
        val assets = File(appFlutter, "flutter_assets").apply { mkdirs() }

        invalidator(sandboxExecutingIn(temporaryFolder.root)).invalidate()

        assertTrue(backup.isFile)
        assertTrue(assets.isDirectory)
    }

    @Test
    fun `unavailable sandbox fails instead of reporting success`() {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        Mockito.`when`(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.UNAVAILABLE)
        Mockito.`when`(sandbox.unavailableReason).thenReturn("run-as missing")

        val error = assertFailsWith<IllegalStateException> { invalidator(sandbox).invalidate() }

        assertTrue(error.message.orEmpty().contains("run-as missing"))
        Mockito.verify(sandbox, Mockito.never()).exec(Mockito.anyString(), Mockito.anyBoolean())
    }

    @Test
    fun `remaining timestamp after deletion fails the deployment`() {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        Mockito.`when`(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.RUN_AS)
        Mockito.`when`(sandbox.exec(Mockito.anyString(), Mockito.anyBoolean()))
            .thenReturn("__JUGG_FLUTTER_JIT_CACHE_FAILED__ app_flutter/res_timestamp-1-1789261483352")

        val error = assertFailsWith<IllegalStateException> { invalidator(sandbox).invalidate() }

        assertTrue(error.message.orEmpty().contains("app_flutter/res_timestamp-1-1789261483352"))
    }

    @Test
    fun `unreported invalidation result fails the deployment`() {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        Mockito.`when`(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.RUN_AS)
        Mockito.`when`(sandbox.exec(Mockito.anyString(), Mockito.anyBoolean())).thenReturn("")

        assertFailsWith<IllegalStateException> { invalidator(sandbox).invalidate() }
    }

    private fun appFlutterDir(): File {
        return File(temporaryFolder.root, "app_flutter").apply { mkdirs() }
    }

    private fun invalidator(sandbox: AppSandboxExecutor): FlutterJitCacheInvalidator {
        return FlutterJitCacheInvalidator(sandbox, Mockito.mock(Logger::class.java))
    }

    /**
     * App sandbox permission handling is covered by [com.sickworm.intellij.jugg.deploy.AppSandboxExecutorTest].
     * This stub only needs a real `app_flutter` directory to run the fixed invalidation command against.
     */
    private fun sandboxExecutingIn(appDataDir: File): AppSandboxExecutor {
        val sandbox = Mockito.mock(AppSandboxExecutor::class.java)
        Mockito.`when`(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.RUN_AS)
        Mockito.`when`(sandbox.exec(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer { invocation ->
            val process = ProcessBuilder("/bin/sh", "-c", invocation.getArgument<String>(0))
                .directory(appDataDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        }
        return sandbox
    }
}
