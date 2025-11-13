package com.sickworm.jugg.demo.testcase.constref

class KtInvoker {

    fun invokeKt() {
        KtClass.VAR_INT
        KtClass.VAR_STRING
    }

    fun invokeJava() {
        JavaClass.VAR_INT
    }
}