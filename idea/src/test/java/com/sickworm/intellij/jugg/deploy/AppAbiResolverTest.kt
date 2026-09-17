package com.sickworm.intellij.jugg.deploy

import com.android.tools.deploy.proto.Deploy
import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files

class AppAbiResolverTest {

    @Test
    fun `running process ABI has highest priority`() {
        assertEquals(
            Deploy.Arch.ARCH_32_BIT,
            resolver("primaryCpuAbi=arm64-v8a").resolve(
                PACKAGE_NAME, Deploy.Arch.ARCH_32_BIT, "ARCH_64_BIT", false, "arm64-v8a",
            ),
        )
    }

    @Test
    fun `installed package ABI is used when app is stopped`() {
        assertEquals(
            Deploy.Arch.ARCH_32_BIT,
            resolver("primaryCpuAbi=armeabi-v7a").resolve(
                PACKAGE_NAME, Deploy.Arch.ARCH_UNKNOWN, "ARCH_UNKNOWN", false, "arm64-v8a",
            ),
        )
    }

    @Test
    fun `manifest 32 bit preference precedes APK and device defaults`() {
        val adb = FakeAdb("primaryCpuAbi=arm64-v8a")
        assertEquals(
            Deploy.Arch.ARCH_32_BIT,
            AppAbiResolver(adb, Mockito.mock(Logger::class.java)).resolve(
                PACKAGE_NAME, Deploy.Arch.ARCH_UNKNOWN, "ARCH_64_BIT", true, "arm64-v8a",
            ),
        )
        assertEquals(0, adb.shellCommandCount)
    }

    @Test
    fun `APK native libraries precede device default`() {
        val adb = FakeAdb("primaryCpuAbi=arm64-v8a")
        assertEquals(
            Deploy.Arch.ARCH_32_BIT,
            AppAbiResolver(adb, Mockito.mock(Logger::class.java)).resolve(
                PACKAGE_NAME, Deploy.Arch.ARCH_UNKNOWN, "ARCH_32_BIT", false, "arm64-v8a",
            ),
        )
        assertEquals(0, adb.shellCommandCount)
    }

    @Test
    fun `32 bit ARM device resolves native free stopped app`() {
        assertEquals(
            Deploy.Arch.ARCH_32_BIT,
            resolver().resolve(
                PACKAGE_NAME, Deploy.Arch.ARCH_UNKNOWN, "ARCH_UNKNOWN", false, "armeabi-v7a",
            ),
        )
    }

    @Test
    fun `unknown evidence keeps 64 bit final fallback`() {
        assertEquals(
            Deploy.Arch.ARCH_64_BIT,
            resolver().resolve(
                PACKAGE_NAME, Deploy.Arch.ARCH_UNKNOWN, "ARCH_UNKNOWN", false, "unknown",
            ),
        )
    }

    @Test
    fun `ABI cache uses the complete APK fingerprint`() {
        val baseApk = Files.createTempFile("jugg-abi-base-", ".apk").toFile()
        val splitApk = Files.createTempFile("jugg-abi-split-", ".apk").toFile()
        try {
            baseApk.writeBytes(byteArrayOf(1))
            splitApk.writeBytes(byteArrayOf(2))
            val cache = AppAbiCache()
            val key = cache.createKey("serial", PACKAGE_NAME, listOf(baseApk.path, splitApk.path))
            cache.put(key, Deploy.Arch.ARCH_32_BIT)

            val reorderedKey = cache.createKey("serial", PACKAGE_NAME, listOf(splitApk.path, baseApk.path))
            assertEquals(Deploy.Arch.ARCH_32_BIT, cache.get(reorderedKey))

            baseApk.appendBytes(byteArrayOf(3))
            val changedKey = cache.createKey("serial", PACKAGE_NAME, listOf(baseApk.path, splitApk.path))
            assertNull(cache.get(changedKey))
        } finally {
            baseApk.delete()
            splitApk.delete()
        }
    }

    @Test
    fun `cache hit skips APK facts and installed package query`() {
        val adb = FakeAdb("primaryCpuAbi=armeabi-v7a")
        val cache = AppAbiCache()
        val apk = Files.createTempFile("jugg-abi-cache-", ".apk").toFile()
        var apkFactsCalls = 0
        try {
            apk.writeBytes(byteArrayOf(1))
            cache.put(cache.createKey("serial", PACKAGE_NAME, listOf(apk.path)), Deploy.Arch.ARCH_32_BIT)

            val resolution = AppAbiResolver(adb, Mockito.mock(Logger::class.java)).resolveWithCache(
                cache = cache,
                deviceSerial = "serial",
                packageName = PACKAGE_NAME,
                apkPaths = listOf(apk.path),
                processArch = Deploy.Arch.ARCH_UNKNOWN,
                deviceAbi = "arm64-v8a",
                apkFacts = {
                    apkFactsCalls++
                    AppAbiResolver.ApkFacts(Deploy.Arch.ARCH_64_BIT.name, false)
                },
            )

            assertEquals(Deploy.Arch.ARCH_32_BIT, resolution.arch)
            assertEquals("cache", resolution.source)
            assertEquals(0, apkFactsCalls)
            assertEquals(0, adb.shellCommandCount)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `stopped process uses APK native libraries before installed package`() {
        val adb = FakeAdb("primaryCpuAbi=arm64-v8a")
        val cache = AppAbiCache()
        val apk = Files.createTempFile("jugg-abi-apk-", ".apk").toFile()
        try {
            apk.writeBytes(byteArrayOf(1))
            val resolution = AppAbiResolver(adb, Mockito.mock(Logger::class.java)).resolveWithCache(
                cache = cache,
                deviceSerial = "serial",
                packageName = PACKAGE_NAME,
                apkPaths = listOf(apk.path),
                processArch = Deploy.Arch.ARCH_UNKNOWN,
                deviceAbi = "arm64-v8a",
                apkFacts = { AppAbiResolver.ApkFacts(Deploy.Arch.ARCH_32_BIT.name, false) },
            )

            assertEquals(Deploy.Arch.ARCH_32_BIT, resolution.arch)
            assertEquals("apk_native_libraries", resolution.source)
            assertEquals(0, adb.shellCommandCount)
            assertEquals(Deploy.Arch.ARCH_32_BIT, cache.get(cache.createKey("serial", PACKAGE_NAME, listOf(apk.path))))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `transient installed package query failure is not cacheable`() {
        val adb = Mockito.mock(IDeviceAdb::class.java)
        Mockito.`when`(adb.execAdbShellCmd(Mockito.anyString())).thenThrow(IllegalStateException("device offline"))

        val resolution = AppAbiResolver(adb, Mockito.mock(Logger::class.java)).resolveDetailed(
            PACKAGE_NAME, Deploy.Arch.ARCH_UNKNOWN, "ARCH_UNKNOWN", false, "arm64-v8a",
        )

        assertEquals(Deploy.Arch.ARCH_64_BIT, resolution.arch)
        assertFalse(resolution.cacheable)
    }

    private fun resolver(packageDump: String = "primaryCpuAbi=null"): AppAbiResolver {
        return AppAbiResolver(FakeAdb(packageDump), Mockito.mock(Logger::class.java))
    }

    private class FakeAdb(private val packageDump: String) : IDeviceAdb {
        var shellCommandCount: Int = 0
            private set

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            shellCommandCount++
            return packageDump
        }
        override fun execAdbShellScript(cmd: String): String = ""
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_UNKNOWN"
        override fun getProperty(name: String): String? = null
    }

    companion object {
        private const val PACKAGE_NAME = "com.example.app"
    }
}
