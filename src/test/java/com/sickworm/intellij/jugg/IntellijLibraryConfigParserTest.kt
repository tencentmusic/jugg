package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.mock.intellijLibraryDir
import com.sickworm.intellij.jugg.project.IntellijLibraryConfigParser
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
        // it depends on what version of Android Studio you opened
        assertTrue(result.size == 46 || result.size == 50)
        result.forEach {
            val isExists = File(it).exists()
            println("file: $it, exists: $isExists")
            assertTrue(isExists)
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