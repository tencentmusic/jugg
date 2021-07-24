package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File

class DexTest {

    private val javaCompileTest = JavaCompileTest()

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun dexer() {
        javaCompileTest.javaCompile()
        dexAndCheck()
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
            DexFileMaker(androidBuildTools).dex(stagingDir, dexFile, classFile)
            assert(dexFile.exists() && dexFile.length() > 0)
        }
    }
}