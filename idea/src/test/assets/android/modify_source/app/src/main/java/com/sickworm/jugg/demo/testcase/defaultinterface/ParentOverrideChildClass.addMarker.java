package com.sickworm.jugg.demo.testcase.defaultinterface;

public class ParentOverrideChildClass extends ParentOverrideBaseClass
        implements ParentOverrideChildInterface {

    public String getMarker() {
        return "changed";
    }
}
