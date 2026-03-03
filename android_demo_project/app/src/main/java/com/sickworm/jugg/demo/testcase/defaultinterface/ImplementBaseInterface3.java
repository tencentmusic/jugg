package com.sickworm.jugg.demo.testcase.defaultinterface;

public interface ImplementBaseInterface3 extends DefaultInterface {

    default public void func2() {
        System.out.println("func2");
    }
}
