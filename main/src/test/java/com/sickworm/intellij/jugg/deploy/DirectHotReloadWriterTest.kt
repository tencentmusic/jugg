package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.util.zip.ZipFile

class DirectHotReloadWriterTest {

    @Test
    fun `writer should create descriptor to dex request before staging`() {
        val adb = RecordingDirectAdb()
        val logger = Mockito.mock(Logger::class.java)
        val sandbox = AppSandboxExecutor(adb, "com.example.app", logger)
        val result = DirectHotReloadWriter(adb, logger, sandbox).apply(
            packageName = "com.example.app",
            pid = 123,
            newClasses = emptyList(),
            modifiedClasses = listOf(
                DirectHotReloadClass("Lcom/example/Foo;", "dex\n035\u0000".toByteArray()),
            ),
            refreshResources = true,
            restartActivity = true,
        )

        assertFalse(result.success)
        assertEquals(listOf("request.txt", "dex/modified/0.dex"), adb.zipEntries)
        assertEquals(
            "JUGG_HOT_RELOAD_V4\nREFRESH_RESOURCES\t1\nRESTART_ACTIVITY\t1\n" +
                "MODIFIED\tLcom/example/Foo;\tdex/modified/0.dex\n",
            adb.requestText,
        )
        assertTrue(adb.scripts.any { it.contains("code_cache/jugg_hot_reload/") })
    }

    @Test
    fun `writer should preserve code only Hot Reload semantics`() {
        val adb = RecordingDirectAdb()
        val logger = Mockito.mock(Logger::class.java)
        val sandbox = AppSandboxExecutor(adb, "com.example.app", logger)

        DirectHotReloadWriter(adb, logger, sandbox).apply(
            packageName = "com.example.app",
            pid = 123,
            newClasses = emptyList(),
            modifiedClasses = listOf(
                DirectHotReloadClass("Lcom/example/Foo;", "dex\n035\u0000".toByteArray()),
            ),
            refreshResources = false,
            restartActivity = false,
        )

        assertTrue(adb.requestText.startsWith(
            "JUGG_HOT_RELOAD_V4\nREFRESH_RESOURCES\t0\nRESTART_ACTIVITY\t0\n",
        ))
    }

    @Test
    fun `writer should allow resource-only runtime apply`() {
        val adb = RecordingDirectAdb()
        val logger = Mockito.mock(Logger::class.java)
        val sandbox = AppSandboxExecutor(adb, "com.example.app", logger)

        DirectHotReloadWriter(adb, logger, sandbox).apply(
            packageName = "com.example.app",
            pid = 123,
            newClasses = emptyList(),
            modifiedClasses = emptyList(),
            refreshResources = true,
            restartActivity = true,
        )

        assertEquals(listOf("request.txt"), adb.zipEntries)
        assertEquals(
            "JUGG_HOT_RELOAD_V4\nREFRESH_RESOURCES\t1\nRESTART_ACTIVITY\t1\n",
            adb.requestText,
        )
    }

    @Test
    fun `writer should send new classes separately from modified classes`() {
        val adb = RecordingDirectAdb()
        val logger = Mockito.mock(Logger::class.java)
        val sandbox = AppSandboxExecutor(adb, "com.example.app", logger)

        DirectHotReloadWriter(adb, logger, sandbox).apply(
            packageName = "com.example.app",
            pid = 123,
            newClasses = listOf(
                DirectHotReloadClass("com.example.NewClass", "dex\n035\u0000".toByteArray()),
            ),
            modifiedClasses = listOf(
                DirectHotReloadClass("Lcom/example/Foo;", "dex\n035\u0000".toByteArray()),
            ),
            refreshResources = false,
            restartActivity = true,
        )

        assertEquals(
            listOf("request.txt", "dex/new/0.dex", "dex/modified/0.dex"),
            adb.zipEntries,
        )
        assertEquals(
            "JUGG_HOT_RELOAD_V4\nREFRESH_RESOURCES\t0\nRESTART_ACTIVITY\t1\n" +
                "NEW\tcom.example.NewClass\tdex/new/0.dex\n" +
                "MODIFIED\tLcom/example/Foo;\tdex/modified/0.dex\n",
            adb.requestText,
        )
    }

    @Test
    fun `writer should allow empty Apply Changes request`() {
        val adb = RecordingDirectAdb()
        val logger = Mockito.mock(Logger::class.java)
        val sandbox = AppSandboxExecutor(adb, "com.example.app", logger)

        DirectHotReloadWriter(adb, logger, sandbox).apply(
            packageName = "com.example.app",
            pid = 123,
            newClasses = emptyList(),
            modifiedClasses = emptyList(),
            refreshResources = false,
            restartActivity = false,
        )

        assertEquals(listOf("request.txt"), adb.zipEntries)
        assertEquals(
            "JUGG_HOT_RELOAD_V4\nREFRESH_RESOURCES\t0\nRESTART_ACTIVITY\t0\n",
            adb.requestText,
        )
    }

    private class RecordingDirectAdb : IDeviceAdb {
        var zipEntries = emptyList<String>()
        var requestText = ""
        val scripts = mutableListOf<String>()
        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            return when {
                cmd.startsWith("dumpsys package ") -> "dataDir=/data/user/0/com.example.app"
                else -> ""
            }
        }

        override fun execAdbShellScript(cmd: String): String {
            scripts += cmd
            return when {
                cmd.contains("__JUGG_RUN_AS_OK__") ->
                    "__JUGG_RUN_AS_OK__:1000\n__JUGG_RUN_AS_CONTEXT__:ctx|ctx"
                cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> "__JUGG_DIRECT_SANDBOX_OK__"
                else -> "staging failed\n__JUGG_APP_SANDBOX_REPAIR_START__\n" +
                    "__JUGG_APP_SANDBOX_REPAIRED__"
            }
        }

        override fun push(from: File, to: String): Boolean {
            ZipFile(from).use { zip ->
                zipEntries = zip.entries().asSequence().map { it.name }.toList()
                requestText = zip.getInputStream(zip.getEntry("request.txt")).bufferedReader().readText()
            }
            return true
        }

        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
