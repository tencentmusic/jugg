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

    private val apkFile = File(assetsAndroidDir, "app/build/outputs/apk/debug/app-debug.apk")

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun testAaptDaemonInvoker() {
        val reader = Aapt2DaemonInvoker(logger)
        val result = reader.invoke("dump resources ${apkFile.absolutePath}")
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)
        reader.release()
    }

    @Test
    fun testAaptDaemonInvokerMultiInvoke() {
        val reader = Aapt2DaemonInvoker(logger)
        var result = reader.invoke("dump resources ${apkFile.absolutePath}")
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)

        result = reader.invoke("dump packagename ${apkFile.absolutePath}")
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)

        result = reader.invoke("dump resources ${apkFile.absolutePath}")
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
        assertEquals("com.sickworm.jugg.demo.testcase.compose.MainComposeActivity", reader.getDefaultActivity())
    }
}