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
        Dexer().dex()
        val dexFile = File("$buildDir/out.dex")
        assert(dexFile.exists() && dexFile.length() > 0)
    }
}