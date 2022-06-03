package com.sickworm.intellij.jugg.mock

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.ClientData
import com.android.ddmlib.IDevice
import com.android.ddmlib.Log
import com.sickworm.intellij.jugg.compiler.isWindows
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

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
    }

    fun getDeviceList(): List<IDevice> {
        return androidDebugBridge.devices.toList()
    }

    fun waitingForDeviceOfLaunchedApp(exceptPackageName: String, maxWaitingMills: Int = 5000): IDevice? {
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

    private fun getLaunchedApp(exceptPackageName: String): IDevice? {
        val devices = androidDebugBridge.devices
        val targetDevice = devices.find { device ->
            device.clients.any { client ->
                client.clientData.packageName == exceptPackageName
            }
        }
        if (targetDevice != null) {
            return targetDevice
        }

        // work around
        // AndroidDebugBridge autofill ClientData is not reliable,
        // (mSelector.select() got 0 in MonitorThread)
        // we use am to fill it manually
        val pid = getPidByPackageName(exceptPackageName)
        if (pid > 0) {
            println("read $exceptPackageName pid from am: $pid")
            devices.forEach { device ->
                device.clients.forEach { client ->
                    if (client.clientData.pid == pid) {
                        // found matched pid, set it and return
                        // can't get it directly
                        // "am dump" can get mRequiredAbi=arm64-v8a
                        // tired of workaround :(
                        client.clientData.abi = "64-bit"
                        client.clientData.setNames(ClientData.Names(
                            exceptPackageName,
                            0, // don't care
                            exceptPackageName))
                        return device
                    }
                }
            }
        }

        return null
    }

    private var pattern = Pattern.compile(" *pid=(\\d+)(\r?\n.*)*")
    private fun getPidByPackageName(packageName: String): Int {
        val cmd = if (isWindows) "cmd /C adb shell am dump p $packageName | findstr pid"
            else "adb shell am dump p $packageName | grep pid"
        val process = Runtime.getRuntime()
            .exec(cmd)
        val result = String(process.inputStream.readBytes())
        if (result.isNotEmpty()) {
            // e.g. "     pid=24114"
            val matcher = pattern.matcher(result)
            if (matcher.matches()) {
                val pid = matcher.group(1)
                if (pid != null) {
                    return pid.toInt()
                }
            }
        }
        process.waitFor()

        return -1
    }
}