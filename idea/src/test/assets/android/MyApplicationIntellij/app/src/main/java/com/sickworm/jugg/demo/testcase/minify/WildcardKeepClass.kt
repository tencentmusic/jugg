package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Wildcard keep rules.
 * Tests keep rules with wildcards that match patterns (e.g., prefix*).
 *
 * ProGuard rule: -keepclassmembers class ...WildcardKeepClass { *prefix*; }
 */
class WildcardKeepClass {

    /**
     * Field with prefix - should be kept due to wildcard rule.
     */
    var prefixKeptField: String = ""

    /**
     * Field without prefix - should be obfuscated.
     */
    var otherField: String = ""

    /**
     * Method with prefix - should be kept due to wildcard rule.
     */
    fun prefixKeptMethod(): String {
        return "prefixKeptMethod: $prefixKeptField"
    }

    /**
     * Method without prefix - should be obfuscated.
     */
    fun otherMethod(): String {
        return "otherMethod: $otherField"
    }
}
