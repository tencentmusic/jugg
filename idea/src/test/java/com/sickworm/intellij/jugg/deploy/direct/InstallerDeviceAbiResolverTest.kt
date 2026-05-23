package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallerDeviceAbiResolverTest {

    @Test
    fun `resolve should prefer primary cpu abi property`() {
        val adb = FakePropertyAdb(
            properties = mapOf(
                "ro.product.cpu.abi" to "arm64-v8a",
                "ro.product.cpu.abi2" to "armeabi-v7a",
            ),
        )

        assertEquals("arm64-v8a", InstallerDeviceAbiResolver.resolve(adb))
    }

    @Test
    fun `resolve should fall back to secondary cpu abi property`() {
        val adb = FakePropertyAdb(
            properties = mapOf(
                "ro.product.cpu.abi" to "unknown",
                "ro.product.cpu.abi2" to "x86_64",
            ),
        )

        assertEquals("x86_64", InstallerDeviceAbiResolver.resolve(adb))
    }

    private class FakePropertyAdb(
        private val properties: Map<String, String>,
    ) : IDeviceAdb {
        override val displayName: String? = null
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = ""
        override fun push(from: java.io.File, to: String): Boolean = true
        override fun pull(from: String, to: java.io.File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: java.io.File): String? = null
        override fun getArch(packageName: String): String = "ARCH_UNKNOWN"
        override fun getProperty(name: String): String? = properties[name]
    }
}
