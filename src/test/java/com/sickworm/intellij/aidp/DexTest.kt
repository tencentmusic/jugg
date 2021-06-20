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
        val buildPath = "src/test/build"
        val outputPath = "src/test/build/dex"
        Dexer().dex(buildPath, outputPath)
        val dexFile = File("$outputPath/out.dex")
        assert(dexFile.exists() && dexFile.length() > 0)
    }
}