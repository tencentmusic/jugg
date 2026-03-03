package com.sickworm.jugg.demo.testcase.minify

import java.io.Serializable

/**
 * Test case: Serializable class.
 * Serializable classes often need special keep rules to preserve field names
 * for serialization/deserialization to work correctly.
 *
 * ProGuard rule: -keepclassmembers class * implements java.io.Serializable { ... }
 */
class SerializableClass : Serializable {

    /**
     * This field should be kept for serialization.
     */
    var serializedField: String = ""

    /**
     * Transient fields are not serialized, so they can be obfuscated.
     */
    @Transient
    var transientField: String = ""

    companion object {
        private const val serialVersionUID = 1L
    }
}
