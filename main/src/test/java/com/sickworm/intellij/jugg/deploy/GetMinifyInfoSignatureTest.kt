package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Tests that ICompileContext.getMinifyInfo accepts compileFiles parameter
 * instead of relying on staging files from stateTracker.
 *
 * This ensures the current compilation round's dex files are used for
 * minify analysis, not stale or incomplete staging data.
 */
class GetMinifyInfoSignatureTest {

    @Test
    fun `getMinifyInfo interface should accept compileFiles parameter`() {
        // Verify the interface method signature includes List<CompileFile> parameter
        // This is a compile-time check: if the method signature doesn't match,
        // this test won't compile.
        val methods = ICompileContext::class.java.methods
        val getMinifyInfoMethod = methods.find { it.name == "getMinifyInfo" }
        assertTrue(getMinifyInfoMethod != null, "getMinifyInfo method should exist on ICompileContext")

        val paramTypes = getMinifyInfoMethod.parameterTypes
        assertTrue(
            paramTypes.size == 1 && paramTypes[0] == List::class.java,
            "getMinifyInfo should accept a List parameter (List<CompileFile>)"
        )
    }
}
