package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compiler.source.DexFileMaker
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

class DexTest {

    private val javaCompileTest = JavaCompileTest()

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun dexer() {
        javaCompileTest.javaCompile()
        repeat(100) {
            dexAndCheck()
        }
    }

    @Test
    fun dexMultipleFiles() {
        JavaCompileTest().javaCompileMultiFiles()
        dexAndCheck()
    }

    private fun dexAndCheck() {
        val classesFiles = stagingDir.listFilesRecursively()

        // ART TI requires one .dex file only contains one .class file
        classesFiles.forEach { classFile ->
            val dexFile = classFile.changeBaseDir(stagingDir, stagingDir, "dex")
            DexFileMaker().dex(stagingDir, classFile)
            assertTrue(dexFile.exists() && dexFile.length() > 0)
            dexFile.delete()
        }
    }
}