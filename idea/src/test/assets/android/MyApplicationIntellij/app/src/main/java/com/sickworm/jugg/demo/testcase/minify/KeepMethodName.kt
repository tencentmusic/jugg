package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Keep specific method name.
 * The class name can be obfuscated, but specific methods are preserved.
 *
 * ProGuard rule: -keepclassmembers class ...KeepMethodName { void keptMethod(); }
 */
class KeepMethodName {

    private var internalState: Int = 0

    /**
     * This method should be kept (preserved name).
     */
    fun keptMethod() {
        internalState++
    }

    /**
     * This method should be obfuscated.
     */
    fun obfuscatedMethod(): Int {
        return internalState
    }
}
