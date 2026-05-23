package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

class DirectOverlayWriterTest {

    @Test
    fun `write should push zip and run atomic overlay script`() {
        val adb = RecordingAdb("__JUGG_DIRECT_OVERLAY__ OK")
        val writer = DirectOverlayWriter(adb, Mockito.mock(Logger::class.java))
        val request = DirectOverlayWriteRequest(
            packageName = "com.example.app",
            expectedOverlayId = "old-id",
            overlayId = "new-id",
            files = listOf(
                DirectOverlayWriteFile("base.apk/res/layout/main.xml", "layout".toByteArray()),
                DirectOverlayWriteFile("com.example.Foo.dex", "dex".toByteArray()),
            ),
        )

        val result = writer.write(request)

        assertEquals(DirectOverlayWriteResult.SUCCESS, result)
        assertEquals(1, adb.pushedZipEntries.size)
        assertEquals(
            listOf("base.apk/res/layout/main.xml", "com.example.Foo.dex"),
            adb.pushedZipEntries.single(),
        )
        assertTrue(adb.lastScript.contains("run-as com.example.app sh -c"))
        assertTrue(adb.lastScript.contains("old-id"))
        assertTrue(adb.lastScript.contains("new-id"))
        assertTrue(adb.lastScript.contains("rm -f"))
        assertTrue(adb.lastScript.contains("overlay_dir=code_cache/.overlay"))
        assertTrue(adb.lastScript.contains("\$overlay_dir/id"))
        assertTrue(adb.lastScript.contains("unzip -oq"))
        assertTrue(adb.lastScript.contains("find \"\$overlay_dir\" -type f -name '*.dex' -exec chmod 0444 {} +"))
        assertTrue(adb.commands.contains("mkdir -p /data/local/tmp/jugg"))
        assertTrue(adb.commands.contains("rm -f /data/local/tmp/jugg/direct-overlay-*.zip"))
    }

    @Test
    fun `write should fail when device script does not report success`() {
        val adb = RecordingAdb("__JUGG_DIRECT_OVERLAY__ MISMATCH")
        val writer = DirectOverlayWriter(adb, Mockito.mock(Logger::class.java))
        val request = DirectOverlayWriteRequest(
            packageName = "com.example.app",
            expectedOverlayId = "old-id",
            overlayId = "new-id",
            files = listOf(DirectOverlayWriteFile("com.example.Foo.dex", "dex".toByteArray())),
        )

        assertEquals(DirectOverlayWriteResult.SKIPPED, writer.write(request))
    }

    @Test
    fun `write should reject unsafe package name before pushing files`() {
        val adb = RecordingAdb("__JUGG_DIRECT_OVERLAY__ OK")
        val writer = DirectOverlayWriter(adb, Mockito.mock(Logger::class.java))
        val request = DirectOverlayWriteRequest(
            packageName = "com.example.app;rm -rf /",
            expectedOverlayId = "old-id",
            overlayId = "new-id",
            files = listOf(DirectOverlayWriteFile("com.example.Foo.dex", "dex".toByteArray())),
        )

        assertEquals(DirectOverlayWriteResult.SKIPPED, writer.write(request))
        assertTrue(adb.pushedZipEntries.isEmpty())
    }

    @Test
    fun `write should serialize concurrent writes globally`() {
        val adb = ConcurrencyTrackingAdb()
        val logger = Mockito.mock(Logger::class.java)
        val request = DirectOverlayWriteRequest(
            packageName = "com.example.app",
            expectedOverlayId = "old-id",
            overlayId = "new-id",
            files = listOf(DirectOverlayWriteFile("com.example.Foo.dex", "dex".toByteArray())),
        )
        val pool = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        try {
            repeat(8) {
                pool.submit {
                    startGate.await()
                    DirectOverlayWriter(adb, logger).write(request)
                }
            }
            startGate.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertEquals(1, adb.maxConcurrentWrites.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `write should report dirty failure after overlay mutation starts`() {
        val adb = RecordingAdb("__JUGG_DIRECT_OVERLAY__ APPLYING")
        val writer = DirectOverlayWriter(adb, Mockito.mock(Logger::class.java))
        val request = DirectOverlayWriteRequest(
            packageName = "com.example.app",
            expectedOverlayId = "old-id",
            overlayId = "new-id",
            files = listOf(DirectOverlayWriteFile("com.example.Foo.dex", "dex".toByteArray())),
        )

        assertEquals(DirectOverlayWriteResult.FAILED_DIRTY, writer.write(request))
    }

    private class ConcurrencyTrackingAdb : IDeviceAdb {
        val maxConcurrentWrites = AtomicInteger(0)
        private val activeWrites = AtomicInteger(0)

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            trackActive {
                Thread.sleep(10)
            }
            return ""
        }

        override fun execAdbShellScript(cmd: String): String {
            trackActive {
                Thread.sleep(50)
            }
            return "__JUGG_DIRECT_OVERLAY__ OK"
        }

        override fun push(from: File, to: String): Boolean {
            trackActive {
                Thread.sleep(10)
            }
            return true
        }

        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null

        private inline fun trackActive(block: () -> Unit) {
            val active = activeWrites.incrementAndGet()
            maxConcurrentWrites.updateAndGet { current -> maxOf(current, active) }
            try {
                block()
            } finally {
                activeWrites.decrementAndGet()
            }
        }
    }

    private class RecordingAdb(private val scriptOutput: String) : IDeviceAdb {
        val pushedZipEntries = mutableListOf<List<String>>()
        val commands = mutableListOf<String>()
        var lastScript: String = ""

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            commands += cmd
            return ""
        }

        override fun execAdbShellScript(cmd: String): String {
            lastScript = cmd
            return scriptOutput
        }

        override fun push(from: File, to: String): Boolean {
            pushedZipEntries += ZipFile(from).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            return true
        }

        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
