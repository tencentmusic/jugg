package com.sickworm.jugg.demo.testcase.constref;

public class JavaInvoker {

    public void invokeKt() {
        int i = KtClass.VAR_INT;
        String s = KtClass.VAR_STRING;
    }

    public void invokeJava() {
        int i = JavaClass.VAR_INT;
        String s = JavaClass.VAR_STRING;
    }
}