package com.sickworm.intellij.aidp.aapt2

import com.sickworm.intellij.aidp.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApkReaderTest {

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun testGenerateR() {
        val apkFile = File(assetsAndroidDir, "app/build/outputs/apk/debug/app-debug.apk")
        val reader = ApkReader(androidBuildTools, apkFile, logger)
        reader.getRFile(tempCompileDir)
        val files = tempCompileDir.listFilesRecursively()
        assertEquals(1, files.size)
        assertEquals("R.java", files[0].name)
        assertTrue(files[0].length() > 0)
    }
}