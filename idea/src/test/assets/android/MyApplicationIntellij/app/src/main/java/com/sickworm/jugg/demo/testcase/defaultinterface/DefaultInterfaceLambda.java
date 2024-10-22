package com.sickworm.jugg.demo.testcase.defaultinterface;

public interface DefaultInterfaceLambda {

    public void fun1();

    default public void func2() {
        System.out.println("func2");
    }
}
