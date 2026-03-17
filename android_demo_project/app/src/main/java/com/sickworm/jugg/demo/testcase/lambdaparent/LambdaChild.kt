package com.sickworm.jugg.demo.testcase.lambdaparent

/**
 * Subclass of LambdaParent that does NOT call any static lambda method from the parent.
 * It should NOT be recompiled when only lambda numbering in LambdaParent changes.
 */
class LambdaChild : LambdaParent() {

    fun extraWork() {
        println("LambdaChild extraWork")
    }
}
