package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File

class IntellijLibraryConfigParserTest {

    @Test
    fun loadLibraryConfig() {
        val result = IntellijLibraryConfigParser(File(assetsAndroidDir, "/.idea/libraries")).parse()
        assert(result != null)
        result!!
        assert(result.size == 50)
        result.forEach {
            println("file: $it")
            assert(File(it).exists())
        }
    }
}