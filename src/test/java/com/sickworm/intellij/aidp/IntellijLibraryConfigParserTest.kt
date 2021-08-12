package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.project.IntellijLibraryConfigParser
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntellijLibraryConfigParserTest {

    @Test
    fun loadLibraryConfig() {
        val result = loadLibraryConfigInTest()
        assertNotNull(result)
        assertEquals(50, result.size)
        result.forEach {
            println("file: $it")
            assertTrue(File(it).exists())
        }
    }

    fun loadLibraryConfigInTest(): List<String>? {
        val result = IntellijLibraryConfigParser(intellijLibraryDir, assetsAndroidDir.absolutePath).parse()
        return result?.map {
            // TODO test compatible
            if (isWindows) it else it.replace("D:/Android", "/Users/wormchen")
        }
    }
}