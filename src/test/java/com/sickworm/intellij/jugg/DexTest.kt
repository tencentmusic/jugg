package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compiler.source.DexFileMaker
import com.sickworm.intellij.jugg.mock.clearBuild
import com.sickworm.intellij.jugg.mock.stagingDir
import org.junit.Before
import org.junit.Test
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
            dexAndCheck(deleteAfterBuild = true)
        }
    }

    @Test
    fun dexMultipleFiles() {
        JavaCompileTest().javaCompileMultiFiles()
        dexAndCheck(deleteAfterBuild = false)
    }

    private fun dexAndCheck(deleteAfterBuild: Boolean) {
        val classesFiles = stagingDir.listFilesRecursively()

        // ART TI requires one .dex file only contains one .class file
        classesFiles.forEach { classFile ->
            val dexFile = classFile.changeBaseDir(stagingDir, stagingDir, "dex")
            val isSuccess = DexFileMaker().dex(stagingDir, classFile)
            assertTrue(isSuccess)
            assertTrue(dexFile.exists() && dexFile.length() > 0)
            if (deleteAfterBuild) {
                dexFile.delete()
            }
        }
    }
}