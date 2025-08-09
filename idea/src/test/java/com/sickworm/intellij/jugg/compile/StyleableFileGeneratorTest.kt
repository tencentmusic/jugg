package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.overlay.StyleableFileGenerator
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class StyleableFileGeneratorTest {

    @Before
    fun setUp() {
        clearBuild()
    }

    @Test
    fun test() {
        val generator = StyleableFileGenerator(logger)
        val outputFile = generator.generateStyleableFile(context, buildDir)
        assertTrue(outputFile != null)
        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > 0)
    }

    @Test
    fun test2() {
        val generator = StyleableFileGenerator(logger)
        val outputFile = generator.generateStyleableFile(
            mockModule.buildPathInfo.rFilePath,
            buildDir)
        assertTrue(outputFile != null)
        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > 0)
    }
}