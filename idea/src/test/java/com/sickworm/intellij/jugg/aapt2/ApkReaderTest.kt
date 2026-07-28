package com.sickworm.intellij.jugg.aapt2

import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApkReaderTest {

    private val apkFile = File(assetsAndroidDir, "build/app/outputs/apk/debug/app-debug.apk")

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun testAaptDaemonInvoker() {
        val reader = Aapt2DaemonInvoker(logger)
        val result = reader.invoke(listOf("dump", "resources", apkFile.absolutePath))
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)
        reader.release()
    }

    @Test
    fun testAaptDaemonInvokerMultiInvoke() {
        val reader = Aapt2DaemonInvoker(logger)
        var result = reader.invoke(listOf("dump", "resources", apkFile.absolutePath))
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)

        result = reader.invoke(listOf("dump", "packagename", apkFile.absolutePath))
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)

        result = reader.invoke(listOf("dump", "resources", apkFile.absolutePath))
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)
        reader.release()
    }

    @Test
    fun testAaptDaemonInvokerWithSpaceInPath() {
        val apkWithSpace = File(buildDir, "apk with space/app debug.apk")
        apkWithSpace.parentFile.mkdirs()
        apkFile.copyTo(apkWithSpace, overwrite = true)

        val reader = Aapt2DaemonInvoker(logger)
        val result = reader.invoke(listOf("dump", "resources", apkWithSpace.absolutePath))

        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)
        reader.release()
    }

    @Test
    fun testGetPackageNameFast() {
        val reader = ApkReader(apkFile, logger)
        val packageName = reader.getPackageName()
        assertEquals(projectInfo.packageName, packageName)
    }

    @Test
    fun testDefaultActivity() {
        val reader = ApkReader(apkFile, logger)
        assertEquals("com.example.myapplication.MainActivity", reader.getDefaultActivity())
    }
}
