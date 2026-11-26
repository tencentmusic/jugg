package com.sickworm.jugg.demo.testcase.defaultinterface;

public class AddedDefaultMethodHost {
    private final AddedDefaultMethodInterface listener = new AddedDefaultMethodInterface() {
        @Override
        public void onChanged() {
            // baseline
        }
    };

    public AddedDefaultMethodInterface getListener() {
        return listener;
    }
}
