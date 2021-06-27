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
        val buildDir = File(classesBuildDir)
        val dexFile = File("src/test/build/dex/out.dex")
        DexFileMaker().dex(buildDir, dexFile)
        assert(dexFile.exists() && dexFile.length() > 0)
    }

    @Test
    fun dexMultipleFiles() {
        JavaCompileTest().javaCompileMultiFiles()
        val classesFiles = File(classesBuildDir).findAllFiles()
        val buildDir = File(classesBuildDir)

        // ART TI requires one .dex file only contains one .class file
        classesFiles.forEach { classFile ->
            val outputPath = classFile.absolutePath
                .replace(classesBuildDir, dexBuildDir)
                .replace(".class", ".dex")
            val dexFile = File(outputPath)
            DexFileMaker().dex(buildDir, dexFile, classFile)
            assert(dexFile.exists() && dexFile.length() > 0)
        }
    }
}