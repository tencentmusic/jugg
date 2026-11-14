package com.sickworm.jugg.demo.testcase.romhiddenapi;

import android.bluetooth.BluetoothProfile;

/** Compile-only reproduction of the reported hidden Bluetooth profile constant. */
public class AccessoriesFragmentCase {
    public int hidHostProfile() {
        return BluetoothProfile.HID_HOST;
    }
}
