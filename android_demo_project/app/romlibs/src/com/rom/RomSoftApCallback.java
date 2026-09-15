package com.rom;

import android.net.wifi.WifiManager;

/** ROM SDK helper compiled against the full framework, subclassing the hidden callback. */
public class RomSoftApCallback extends WifiManager.SoftApCallback {

    @Override
    public void onStateChanged(int state, int failureReason) {
    }
}
