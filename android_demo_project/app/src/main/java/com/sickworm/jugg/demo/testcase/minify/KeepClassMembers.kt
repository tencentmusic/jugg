package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Keep specific class members.
 * The class name can be obfuscated, but specific fields and methods are preserved.
 *
 * ProGuard rule: -keepclassmembers class ...KeepClassMembers { ... }
 */
class KeepClassMembers {

    /**
     * This field should be kept (preserved name).
     */
    var keptField: String = ""

    /**
     * This field should be obfuscated.
     */
    var obfuscatedField: String = ""

    /**
     * This method should be kept (preserved name).
     */
    fun keptMethod(): String {
        return "keptMethod called"
    }

    /**
     * This method should be obfuscated.
     */
    fun obfuscatedMethod(): String {
        return "obfuscatedMethod called"
    }
}
