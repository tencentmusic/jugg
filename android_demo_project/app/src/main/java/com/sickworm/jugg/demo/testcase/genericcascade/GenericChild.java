package com.sickworm.jugg.demo.testcase.genericcascade;

public class GenericChild extends GenericParent<String> {

    public void pingChild() {
        ping();
    }
}
