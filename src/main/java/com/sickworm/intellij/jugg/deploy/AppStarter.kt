package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.activity.DefaultApkActivityLocator

class AppStarter {

    fun startApp(packageName: String, launchedActivity: String, isRestart: Boolean = true) {
        if (isRestart) {
            stopApp(packageName)
        }
        Runtime.getRuntime()
            .exec("adb shell am start -S -n $packageName/$launchedActivity")
            .waitFor()
    }

    fun startDefaultApp(packageName: String, apkProvider: ApkProvider, device: IDevice, isRestart: Boolean = true) {
        val launchedActivity = getDefaultActivity(apkProvider, device)
        startApp(packageName, launchedActivity, isRestart)
    }

    private fun getDefaultActivity(apkProvider: ApkProvider, device: IDevice): String {
        val locator = DefaultApkActivityLocator(apkProvider)
        return locator.getQualifiedActivityName(device)
    }

    private fun stopApp(packageName: String) {
        Runtime.getRuntime()
            .exec("adb shell am force-stop $packageName")
            .waitFor()
    }
}