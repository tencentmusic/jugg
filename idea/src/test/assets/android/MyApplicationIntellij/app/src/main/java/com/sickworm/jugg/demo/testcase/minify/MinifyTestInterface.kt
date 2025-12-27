package com.sickworm.jugg.demo.testcase.minify

/**
 * Interface for testing interface method name preservation.
 * Interface methods that are implemented should have their names preserved
 * to maintain the contract.
 */
interface MinifyTestInterface {

    /**
     * Interface method - name should be preserved in implementations.
     */
    fun interfaceMethod(param: String): String
}
