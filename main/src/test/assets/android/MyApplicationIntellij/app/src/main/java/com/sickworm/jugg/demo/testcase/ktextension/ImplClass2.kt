package com.sickworm.jugg.demo.testcase.ktextension

/**
 * Test case:
 * error: unresolved reference: ext2
 *
 * Reason: *.kotlin_module is overridden after compilation, so extension function is not found.
 */
class ImplClass2 {

    fun fun1() {
        "abc".ext2()
    }
}