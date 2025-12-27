package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Unreferenced class (should be removed by R8).
 * This class is NOT referenced by any other code in the project,
 * so R8 should remove it completely during shrinking.
 *
 * This is used to verify that R8 tree-shaking is working correctly.
 */
class UnreferencedClass {

    var unusedField: String = ""

    fun unusedMethod(): String {
        return "This should never be in the APK"
    }

    companion object {
        const val UNUSED_CONSTANT = "unused"

        fun unusedStaticMethod(): String {
            return "unused static method"
        }
    }
}
