package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File

class DexTest {

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun dexer() {
        JavaCompileTest().javaCompile()
        val buildDir = classPathDir
        val dexFile = File("src/test/build/dex/out.dex")
        DexFileMaker().dex(buildDir, dexFile)
        assert(dexFile.exists() && dexFile.length() > 0)
    }

    @Test
    fun dexMultipleFiles() {
        JavaCompileTest().javaCompileMultiFiles()
        val classesFiles = classPathDir.listFilesRecursively()
        val buildDir = classPathDir

        // ART TI requires one .dex file only contains one .class file
        classesFiles.forEach { classFile ->
            val dexFile = classFile.changeBaseDir(classPathDir, compileDexDir, "dex")
            DexFileMaker().dex(buildDir, dexFile, classFile)
            assert(dexFile.exists() && dexFile.length() > 0)
        }
    }
}