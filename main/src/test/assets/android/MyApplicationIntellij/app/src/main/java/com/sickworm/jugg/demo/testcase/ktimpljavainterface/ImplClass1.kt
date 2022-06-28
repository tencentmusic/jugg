package com.sickworm.jugg.demo.testcase.ktimpljavainterface

/**
 * Test case:
 * error: class 'ImplClass' is not abstract and does not implement abstract member public abstract fun fun1(p0: InnerClass!): Unit defined in com.sickworm.jugg.demo.testcase.ktimpljavainterface.JavaInterface
 */
class ImplClass1 : JavaInterface2 {

    override fun fun1(arg1: KtClass3.InnerClass) {
    }

}