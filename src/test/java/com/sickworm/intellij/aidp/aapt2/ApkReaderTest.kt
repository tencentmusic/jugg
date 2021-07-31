package com.sickworm.intellij.aidp.aapt2

import com.sickworm.intellij.aidp.*
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
    fun testAaptInvoker() {
        val reader = Aapt2Invoker(androidBuildTools)
        val result = reader.invoke("dump resources ${apkFile.absolutePath}")
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)
    }

    @Test
    fun testAaptDaemonInvoker() {
        val reader = Aapt2DaemonInvoker(androidBuildTools, logger)
        val result = reader.invoke("dump resources ${apkFile.absolutePath}")
        assertEquals("", result.errorOutput)
        assertTrue(result.output.isNotEmpty())
        assertTrue(result.isSuccess)
    }

    @Test
    fun testAaptDaemonInvokerMultiInvoke() {
        val reader = Aapt2DaemonInvoker(androidBuildTools, logger)
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
    }

    @Test
    fun testGenerateR() {
        val reader = ApkReader(androidBuildTools, apkFile, logger)
        reader.getRFile(tempCompileDir)
        val files = tempCompileDir.listFilesRecursively()
        assertEquals(1, files.size)
        assertEquals("R.java", files[0].name)
        assertTrue(files[0].length() > 0)
    }
}