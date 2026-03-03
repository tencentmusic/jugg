package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Inner classes.
 * Tests how obfuscation handles inner classes (both static and non-static).
 */
class InnerClassHolder {

    private var holderField: String = ""

    /**
     * Non-static inner class - has implicit reference to outer class.
     */
    inner class InnerClass {
        var innerField: String = ""

        fun innerMethod(): String {
            // Can access outer class members
            return "innerMethod: $innerField, holder: $holderField"
        }
    }

    /**
     * Static nested class - no implicit reference to outer class.
     */
    class StaticInnerClass {
        var staticInnerField: String = ""

        fun staticInnerMethod(): String {
            return "staticInnerMethod: $staticInnerField"
        }
    }

    /**
     * Private inner class - should be fully obfuscated.
     */
    private inner class PrivateInnerClass {
        var privateField: String = ""

        fun privateMethod(): String {
            return "privateMethod: $privateField"
        }
    }

    fun usePrivateInner(): String {
        val inner = PrivateInnerClass()
        inner.privateField = "test"
        return inner.privateMethod()
    }
}
