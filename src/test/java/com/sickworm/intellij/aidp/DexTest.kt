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
        val buildDir = File("src/test/build")
        val dexFile = File("src/test/build/dex/out.dex")
        Dexer().dex(buildDir, dexFile)
        assert(dexFile.exists() && dexFile.length() > 0)
    }
}