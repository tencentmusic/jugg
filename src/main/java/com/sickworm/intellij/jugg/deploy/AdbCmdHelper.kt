package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.activity.DefaultApkActivityLocator
import com.sickworm.intellij.jugg.project.JuggException

object AdbCmdHelper {

    fun startApp(packageName: String, launchedActivity: String, isRestart: Boolean = true) {
        if (isRestart) {
            stopApp(packageName)
        }
        invokeCmdAndCheckResult("adb shell am start -S -n $packageName/$launchedActivity")
    }

    fun startDefaultApp(packageName: String, apkProvider: ApkProvider, device: IDevice, isRestart: Boolean = true) {
        val launchedActivity = getDefaultActivity(apkProvider, device)
        startApp(packageName, launchedActivity, isRestart)
    }

    private fun getDefaultActivity(apkProvider: ApkProvider, device: IDevice): String {
        val locator = DefaultApkActivityLocator(apkProvider)
        return locator.getQualifiedActivityName(device)
    }

    fun stopApp(packageName: String) {
        invokeCmdAndCheckResult("adb shell am force-stop $packageName")
    }

    private fun invokeCmdAndCheckResult(cmd: String) {
        val resultCode = Runtime.getRuntime()
            .exec(cmd)
            .waitFor()
        if (resultCode != 0) {
            throw JuggException.invokeAdbFailed(cmd, resultCode)
        }
    }
}