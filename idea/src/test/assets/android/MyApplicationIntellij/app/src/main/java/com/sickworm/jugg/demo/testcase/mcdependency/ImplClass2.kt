package com.sickworm.jugg.demo.testcase.mcdependency

/**
 * Test case:
 * cannot access class 'com.sickworm.jugg.demo.testcase.mcdependency.IBlastRoomViewListener3'. Check your module classpath for missing or conflicting dependencies
 */
class ImplClass2 {

    private var implClass1: ImplClass1? = null
    private val listener3: Listener3? = null

    init {
        implClass1?.fun1(listener3)
    }
}