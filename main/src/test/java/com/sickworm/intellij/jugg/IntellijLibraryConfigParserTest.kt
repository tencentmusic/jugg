package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.TestProjectDependsLoader
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntellijLibraryConfigParserTest {

    @Test
    fun loadLibraryConfig() {
        val result = loadLibraryConfigInTest()
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Suppress("RedundantNullableReturnType")
    fun loadLibraryConfigInTest(): List<String>? {
        AssembleAndroidProjectOnce.ensure()
        return TestProjectDependsLoader.parse()
    }
}