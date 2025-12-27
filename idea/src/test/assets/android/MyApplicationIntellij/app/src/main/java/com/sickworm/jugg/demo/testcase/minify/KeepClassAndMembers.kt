package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Keep class name and all members.
 * Both the class name and all its members should be preserved.
 *
 * ProGuard rule: -keep class ...KeepClassAndMembers { *; }
 */
class KeepClassAndMembers {

    var keptFieldOne: String = ""

    var keptFieldTwo: Int = 0

    fun keptMethodOne(): String {
        return "keptMethodOne: $keptFieldOne"
    }

    fun keptMethodTwo(): Int {
        return keptFieldTwo
    }

    private fun privateButKept(): String {
        return "privateButKept"
    }
}
