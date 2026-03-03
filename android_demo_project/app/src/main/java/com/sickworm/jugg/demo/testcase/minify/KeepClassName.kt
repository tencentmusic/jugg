package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Keep class name only.
 * The class name "KeepClassName" should be preserved,
 * but fields and methods can be obfuscated.
 *
 * ProGuard rule: -keep class ...KeepClassName
 */
class KeepClassName {

    var obfuscatedField: String = ""

    fun obfuscatedMethod(): String {
        return "obfuscatedMethod called with field: $obfuscatedField"
    }
}
