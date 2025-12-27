package com.sickworm.jugg.demo.testcase.minify

import androidx.annotation.Keep

/**
 * Test case: Keep via @Keep annotation.
 * Fields and methods annotated with @Keep should be preserved.
 * The class itself is not annotated, so the class name can be obfuscated.
 */
class KeepAnnotated {

    /**
     * This field should be kept due to @Keep annotation.
     */
    @Keep
    var keptField: String = ""

    /**
     * This field should be obfuscated (no @Keep annotation).
     */
    var obfuscatedField: String = ""

    /**
     * This method should be kept due to @Keep annotation.
     */
    @Keep
    fun keptMethod(): String {
        return "keptMethod: $keptField"
    }

    /**
     * This method should be obfuscated (no @Keep annotation).
     */
    fun obfuscatedMethod(): String {
        return "obfuscatedMethod: $obfuscatedField"
    }
}
