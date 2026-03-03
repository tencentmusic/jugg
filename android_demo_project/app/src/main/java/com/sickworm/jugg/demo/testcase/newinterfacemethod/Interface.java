package com.sickworm.jugg.demo.testcase.newinterfacemethod;

public interface Interface {
    void fun1();
    void fun2();
    public default void fun3() {
        System.out.println("Interface fun3");
    }
}
