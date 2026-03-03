package com.sickworm.jugg.demo.testcase.defaultinterface;

public class InvokerClass2 {

    public void haha1() {
        haha2(() -> {
            System.out.println("111");
        });
    }


    public void haha2(DefaultInterfaceLambda interface1) {
        interface1.func2();
    }
}
