package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Fully obfuscated class.
 * Both class name and all members should be obfuscated.
 * No keep rules apply to this class.
 */
class FullyObfuscated {

    var fieldOne: String = ""

    var fieldTwo: Int = 0

    fun methodOne(): String {
        return "methodOne: $fieldOne"
    }

    fun methodTwo(param: String): String {
        return "methodTwo: $param, fieldTwo: $fieldTwo"
    }

    private fun privateMethod(): String {
        return "privateMethod"
    }

    companion object {
        const val CONSTANT_VALUE = "constant"

        fun staticMethod(): String {
            return "staticMethod"
        }
    }
}
