package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.activity.DefaultApkActivityLocator
import com.google.common.base.Charsets
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggException
import java.util.concurrent.TimeUnit

class AdbCmdHelper(
    private val device: IDevice,
    ideaLogger: Logger,
    ) {

    private val logger = LogWrapper(ideaLogger).also {
        it.alwaysLogAsDebug(true)
        it.allowVerbose(true)
    }
    private val adb = AdbClient(device, this.logger)

    fun startApp(packageName: String, launchedActivity: String, isRestart: Boolean = true) {
        if (isRestart) {
            stopApp(packageName)
        }
        execAdbShellCmd("am start -S -n $packageName/$launchedActivity")
    }

    fun startDefaultApp(packageName: String, apkProvider: ApkProvider, isRestart: Boolean = true) {
        val launchedActivity = getDefaultActivity(apkProvider, device)
        startApp(packageName, launchedActivity, isRestart)
    }

    private fun getDefaultActivity(apkProvider: ApkProvider, device: IDevice): String {
        val locator = DefaultApkActivityLocator(apkProvider)
        return locator.getQualifiedActivityName(device)
    }

    fun stopApp(packageName: String) {
        execAdbShellCmd("am force-stop $packageName")
    }

    private fun execAdbShellCmd(cmd: String): String {
        try {
            logger.info("adb in: adb shell $cmd")
            val response = adb.shell(
                cmd.split(" ").toTypedArray(),
                null, 5L, TimeUnit.MINUTES)
            if (response.isNotEmpty()) {
                val extraMsg = String(response, Charsets.UTF_8).trim { it <= ' ' }
                logger.info("adb out: $extraMsg")
                return extraMsg
            }
            return ""
        } catch (e: Exception) {
            logger.error(e, "invoke execAdbShellCmd failed")
            throw JuggException.invokeAdbFailed2(cmd, e)
        }
    }
}