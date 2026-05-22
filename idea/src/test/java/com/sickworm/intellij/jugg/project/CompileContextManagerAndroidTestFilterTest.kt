package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.BuildTarget
import org.junit.Assert.*
import org.junit.Test

/** Delegates to [ModulePathMergePolicy] used by CompileContextManager. */
class CompileContextManagerAndroidTestFilterTest {

    @Test
    fun `androidTest module is skipped when buildTarget is APP`() {
        assertTrue(ModulePathMergePolicy.shouldSkipIdeModule("app.androidTest", BuildTarget.APP))
    }

    @Test
    fun `androidTest module is included when buildTarget is ANDROID_TEST`() {
        assertFalse(ModulePathMergePolicy.shouldSkipIdeModule("app.androidTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `test module is always skipped regardless of buildTarget`() {
        assertTrue(ModulePathMergePolicy.shouldSkipIdeModule("app.test", BuildTarget.APP))
        assertTrue(ModulePathMergePolicy.shouldSkipIdeModule("app.test", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `unitTest module is always skipped regardless of buildTarget`() {
        assertTrue(ModulePathMergePolicy.shouldSkipIdeModule("app.unitTest", BuildTarget.APP))
        assertTrue(ModulePathMergePolicy.shouldSkipIdeModule("app.unitTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `regular app module is never skipped`() {
        assertFalse(ModulePathMergePolicy.shouldSkipIdeModule("app", BuildTarget.APP))
        assertFalse(ModulePathMergePolicy.shouldSkipIdeModule("app", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `library module is never skipped`() {
        assertFalse(ModulePathMergePolicy.shouldSkipIdeModule("mylib", BuildTarget.APP))
        assertFalse(ModulePathMergePolicy.shouldSkipIdeModule("mylib", BuildTarget.ANDROID_TEST))
    }
}
