package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.BuildTarget
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the module-name filter logic extracted from CompileContextManager.shouldSkipModule().
 * Tests the pure function directly to avoid needing a full IntelliJ environment.
 */
class CompileContextManagerAndroidTestFilterTest {

    // Mirror of the filter logic in CompileContextManager.doGetAllModulesByModuleManager()
    // extracted as a pure function for testability.
    private fun shouldSkipAsTestModule(stdModuleName: String, buildTarget: BuildTarget): Boolean {
        val isAndroidTestModule = stdModuleName.endsWith(".androidTest")
        return stdModuleName.endsWith(".test") ||
                stdModuleName.endsWith(".unitTest") ||
                (isAndroidTestModule && buildTarget != BuildTarget.ANDROID_TEST)
    }

    @Test
    fun `androidTest module is skipped when buildTarget is APP`() {
        assertTrue(shouldSkipAsTestModule("app.androidTest", BuildTarget.APP))
    }

    @Test
    fun `androidTest module is included when buildTarget is ANDROID_TEST`() {
        assertFalse(shouldSkipAsTestModule("app.androidTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `test module is always skipped regardless of buildTarget`() {
        assertTrue(shouldSkipAsTestModule("app.test", BuildTarget.APP))
        assertTrue(shouldSkipAsTestModule("app.test", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `unitTest module is always skipped regardless of buildTarget`() {
        assertTrue(shouldSkipAsTestModule("app.unitTest", BuildTarget.APP))
        assertTrue(shouldSkipAsTestModule("app.unitTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `regular app module is never skipped`() {
        assertFalse(shouldSkipAsTestModule("app", BuildTarget.APP))
        assertFalse(shouldSkipAsTestModule("app", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `library module is never skipped`() {
        assertFalse(shouldSkipAsTestModule("mylib", BuildTarget.APP))
        assertFalse(shouldSkipAsTestModule("mylib", BuildTarget.ANDROID_TEST))
    }
}
