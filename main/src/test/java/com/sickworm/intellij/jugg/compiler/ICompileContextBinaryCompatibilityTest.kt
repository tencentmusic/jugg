package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.project.BaseCompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull

/** Verifies that custom compilers compiled against the legacy context API remain loadable. */
class ICompileContextBinaryCompatibilityTest {

    @Test
    fun `legacy getDesugarInfo descriptor remains available`() {
        val parameterTypes = arrayOf(List::class.java, ModuleInfo::class.java, File::class.java)

        assertNotNull(ICompileContext::class.java.getMethod("getDesugarInfo", *parameterTypes))
        assertNotNull(BaseCompileContext::class.java.getMethod("getDesugarInfo", *parameterTypes))
    }
}
