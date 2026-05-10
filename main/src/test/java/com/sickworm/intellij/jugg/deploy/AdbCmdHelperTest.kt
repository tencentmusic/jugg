package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class AdbCmdHelperTest {

    @Test
    fun `isAppInstalled should use pm path package output`() {
        val adb = FakeDeviceAdb("package:/data/app/example/base.apk")

        val isInstalled = AdbCmdHelper(adb, Mockito.mock(Logger::class.java)).isAppInstalled("com.example")

        assertTrue(isInstalled)
        assertEquals("pm path com.example", adb.commands.single())
    }

    @Test
    fun `isAppInstalled should return false when pm path has no package output`() {
        val adb = FakeDeviceAdb("")

        val isInstalled = AdbCmdHelper(adb, Mockito.mock(Logger::class.java)).isAppInstalled("com.example")

        assertFalse(isInstalled)
    }

    private class FakeDeviceAdb(
        private val shellOutput: String,
    ) : IDeviceAdb {
        val commands = mutableListOf<String>()

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            commands += cmd
            return shellOutput
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = ""
        override fun getProperty(name: String): String? = null
    }
}
