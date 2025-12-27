package com.sickworm.jugg.demo.testcase.minify

/**
 * Test case: Class implementing an interface.
 * The interface method name should be preserved to maintain the interface contract,
 * but other methods can be obfuscated.
 */
class InterfaceImplementor : MinifyTestInterface {

    /**
     * Implementation of interface method - name should match interface.
     */
    override fun interfaceMethod(param: String): String {
        return "interfaceMethod called with: $param"
    }

    /**
     * Normal method - can be obfuscated.
     */
    fun normalMethod(): String {
        return "normalMethod called"
    }
}
