package com.sickworm.jugg.demo.testcase.defaultinterface;

/**
 * Provides a default value that must not override an implementation inherited from a superclass.
 */
public interface ParentOverrideDefaultInterface {

    default String getPage() {
        return null;
    }
}
