package com.sickworm.intellij.jugg.aapt2

import com.sickworm.intellij.jugg.listFilesRecursively
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.mock.clearBuild
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.tempCompileDir
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
    }

    @Test
    fun testGenerateR() {
        val reader = ApkReader(apkFile, logger)
        reader.getRFile(tempCompileDir)
        val files = tempCompileDir.listFilesRecursively()
        assertEquals(1, files.size)
        assertEquals("R.java", files[0].name)
        assertTrue(files[0].length() > 0)
    }
}