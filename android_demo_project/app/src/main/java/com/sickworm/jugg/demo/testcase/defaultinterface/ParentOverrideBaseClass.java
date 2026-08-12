package com.sickworm.jugg.demo.testcase.defaultinterface;

/**
 * Supplies the concrete implementation inherited by the child class.
 */
public class ParentOverrideBaseClass extends ParentOverrideRootClass
        implements ParentOverrideDefaultInterface {

    @Override
    public String getPage() {
        return "parent-implementation";
    }
}
