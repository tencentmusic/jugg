package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.overlay.RPackageReader
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.mockModule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class RPackageReaderTest {

    @Test
    fun test() {
        val manifestFile = mockModule.manifestFile!!
        val packageName = RPackageReader(manifestFile, logger).readPackageName()
        assertEquals("com.example.myapplication", packageName)
    }
}