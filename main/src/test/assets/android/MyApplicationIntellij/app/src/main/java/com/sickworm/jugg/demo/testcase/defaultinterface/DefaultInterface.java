package com.sickworm.jugg.demo.testcase.defaultinterface;

public interface DefaultInterface {

    default public void func1() {
        System.out.println("func1");
    }

    static public void func2() {
        System.out.println("func2");
    }
}
