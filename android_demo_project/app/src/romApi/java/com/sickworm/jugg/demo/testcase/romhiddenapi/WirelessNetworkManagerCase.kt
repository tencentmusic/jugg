package com.sickworm.jugg.demo.testcase.romhiddenapi

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

/** Compile-only reproduction of the six unresolved ROM Wi-Fi methods in report 752724a0. */
class WirelessNetworkManagerCase {
    fun configure(manager: WifiManager, config: WifiConfiguration): Long {
        WifiInfo.removeDoubleQuotes("ssid")
        manager.connect(1, null)
        manager.forget(1, null)
        manager.disconnectForNetId(1)
        manager.ipConfigurationLostReceived(1)
        return config.getLastConnectedTimestamp()
    }
}
