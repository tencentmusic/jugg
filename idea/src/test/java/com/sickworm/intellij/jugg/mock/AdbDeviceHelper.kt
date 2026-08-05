package com.sickworm.intellij.jugg.mock

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.ClientData
import com.android.ddmlib.IDevice
import com.android.ddmlib.Log
import java.util.concurrent.TimeUnit

/**
 * Init adb, find online device and debuggable app.
 */
class AdbDeviceHelper {

    private lateinit var androidDebugBridge: AndroidDebugBridge

    fun init() {
        Log.setLevel(Log.LogLevel.DEBUG)
        Log.addLogger(object : Log.ILogOutput {
            override fun printLog(logLevel: Log.LogLevel, tag: String?, message: String?) {
                logger.trace("[$logLevel] [$tag] $message")
            }

            override fun printAndPromptLog(logLevel: Log.LogLevel, tag: String?, message: String?) {
                logger.trace("[$logLevel] [$tag] [prompt] $message")
            }
        })

        @Suppress("DEPRECATION") // deprecated for non-test usages
        AndroidDebugBridge.initIfNeeded(true)
        androidDebugBridge = AndroidDebugBridge.createBridge(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        waitForInitialDeviceList()
    }

    fun getDeviceList(): List<IDevice> {
        return androidDebugBridge.devices.toList()
    }

    fun getSelectedDeviceList(): List<IDevice> {
        val onlineDevices = getDeviceList().filter { it.isOnline }
        val serial = System.getenv("JUGG_TEST_DEVICE_SERIAL") ?: System.getenv("ANDROID_SERIAL")
        return listOfNotNull(serial?.let { target -> onlineDevices.find { it.serialNumber == target } }
            ?: onlineDevices.firstOrNull())
    }

    private fun waitForInitialDeviceList(maxWaitingMills: Int = 10_000) {
        var remainWaitingMills = maxWaitingMills
        val waitingGap = 200
        while (remainWaitingMills >= 0 && !androidDebugBridge.hasInitialDeviceList()) {
            Thread.sleep(waitingGap.toLong())
            remainWaitingMills -= waitingGap
        }
    }

    fun waitingForDeviceOfLaunchedApp(exceptPackageName: String, maxWaitingMills: Int = 10_000): IDevice? {
        var remainWaitingMills = maxWaitingMills
        val waitingGap = 500
        while (remainWaitingMills >= 0) {
            val devices = androidDebugBridge.devices
            val clientsString = devices
                .flatMap { it.clients.toList() }
                .map {
                    "${it.clientData.packageName?: "unknown_application"}:${it.clientData.pid}"
                }
            println("waitingForLaunchedApp result: device size ${devices.size}, $clientsString")

            val iDevice = getLaunchedApp(exceptPackageName)
            if (iDevice != null) {
                return iDevice
            }

            Thread.sleep(waitingGap.toLong())
            remainWaitingMills -= waitingGap
        }

        println("waitingForLaunchedApp can not find launched app with package name: $exceptPackageName")
        return null
    }

    fun hasLaunchedApp(packageName: String): Boolean {
        return getLaunchedApp(packageName) != null
    }

    fun getPidOfLaunchedApp(packageName: String): Int {
        return getPidByPackageName(packageName)
    }

    private fun getLaunchedApp(exceptPackageName: String): IDevice? {
        val devices = getSelectedDeviceList()
        val pid = getPidByPackageName(exceptPackageName)
        if (pid <= 0) return null

        val targetDevice = devices.find { device ->
            val client = device.clients.find { it.clientData.pid == pid } ?: return@find false
            client.clientData.abi = "64-bit"
            client.clientData.setNames(ClientData.Names(exceptPackageName, 0, exceptPackageName))
            true
        }
        if (targetDevice != null) {
            return targetDevice
        }

        println("read $exceptPackageName pid from selected device: $pid")
        return devices.singleOrNull()
    }

    private fun getPidByPackageName(packageName: String): Int {
        val device = getSelectedDeviceList().singleOrNull() ?: return -1
        val process = ProcessBuilder("adb", "-s", device.serialNumber, "shell", "pidof", packageName).start()
        val result = String(process.inputStream.readBytes()).trim()
        process.waitFor()
        return result.substringBefore(' ').toIntOrNull() ?: -1
    }
}
