package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File

class IntellijLibraryConfigParserTest {

    @Test
    fun loadLibraryConfig() {
        val result = loadLibraryConfigInTest()
        assert(result != null)
        result!!
        assert(result.size == 50)
        result.forEach {
            println("file: $it")
            assert(File(it).exists())
        }
    }

    fun loadLibraryConfigInTest(): List<String>? {
        val result = IntellijLibraryConfigParser(intellijLibraryDir).parse()
        return result?.map {
            // TODO test compatible
            if (isWindows) it else it.replace("D:/Android", "/Users/wormchen")
        }
    }
}