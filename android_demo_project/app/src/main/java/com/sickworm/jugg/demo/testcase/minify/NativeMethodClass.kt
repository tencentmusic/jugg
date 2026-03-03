package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Class with native methods.
 * Native methods must have their names preserved because they are
 * linked by name with native code.
 *
 * ProGuard rule: -keepclasseswithmembernames class * { native <methods>; }
 */
class NativeMethodClass {

    var nativeField: Int = 0

    /**
     * Native method - name must be preserved for JNI linkage.
     */
    external fun nativeMethod(): Int

    /**
     * Normal method - can be obfuscated.
     */
    fun normalMethod(): String {
        nativeField = 1
        return "normalMethod called"
    }

    companion object {
        init {
            // In a real app, this would load a native library
            // System.loadLibrary("native-lib")
        }
    }
}
