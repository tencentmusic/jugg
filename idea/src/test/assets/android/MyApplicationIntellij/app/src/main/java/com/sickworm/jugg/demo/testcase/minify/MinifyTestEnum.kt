package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Enum class.
 * Enum classes have special handling - enum values are typically kept
 * because they are accessed by name via reflection (e.g., valueOf()).
 */
enum class MinifyTestEnum {
    VALUE_ONE,
    VALUE_TWO,
    VALUE_THREE;

    fun enumMethod(): String {
        System.out.println("call MinifyTestEnum.enumMethod()")
        return "Enum value: $name"
    }

    companion object {
        fun fromString(value: String): MinifyTestEnum? {
            return values().find { it.name == value }
        }
    }
}
