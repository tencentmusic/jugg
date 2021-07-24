package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File

class IntellijLibraryConfigParserTest {

    @Test
    fun loadLibraryConfig() {
        val result = IntellijLibraryConfigParser(intellijLibraryDir).parse()
        assert(result != null)
        result!!
        assert(result.size == 50)
        result.forEach {
            // TODO test compatible
            val path = if (isWindows) it else it.replace("D:/Android", "/Users/wormchen")
            println("file: $path")
            assert(File(path).exists())
        }
    }
}