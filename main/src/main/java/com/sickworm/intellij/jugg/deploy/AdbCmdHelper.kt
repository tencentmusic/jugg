package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance

class AdbCmdHelper(
    private val adb: IDeviceAdb,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("AdbCmdHelper")

    @Suppress("MemberVisibilityCanBePrivate")
    fun startApp(packageName: String, launchedActivity: String, isRestart: Boolean = true) {
        logger.debug("startApp: $packageName, $launchedActivity")
        val restartFlag = if (isRestart) "-S" else ""
        execAdbShellCmd("am start $restartFlag -n $packageName/$launchedActivity")
    }

    fun startDefaultApp(packageName: String, apks: List<ApkInfo>, isRestart: Boolean = true) {
        logger.debug("startDefaultApp: $packageName, apks: $apks, isRestart: $isRestart")
        val apkFile = apks.first().files.first().apkFile
        val launchedActivity = adb.getDefaultLaunchActivity(apkFile)
        if (launchedActivity == null) {
            logger.warn("No default launch activity found for $packageName, won't start App.")
            return
        }
        startApp(packageName, launchedActivity, isRestart)
    }

    fun stopApp(packageName: String) {
        logger.debug("stopApp: $packageName")
        execAdbShellCmd("am force-stop $packageName")
    }

    fun isAppForeground(packageName: String): Boolean {
        var startFind = false
        val result = execAdbShellCmd("dumpsys activity recents")
        result.lines().forEach {
            if (it.startsWith("  * Recent #0")) {
                startFind = true
            } else if (it.startsWith("  * Recent #")) {
                // reach end
                return false
            }

            if (startFind) {
                if (it.contains("$packageName/")) {
                    return true
                }
            }
        }
        return false
    }

    fun dumpErrorLog(limit: Int = 100000): String {
        logger.debug("dumpErrorLog: $limit")
        return execAdbShellCmd("logcat -t$limit -s \"jugg *:W\"")
    }

    fun deleteDeployedDexFile(packageName: String, filePath: String) {
        logger.debug("deleteDeployedDexFile: $packageName, $filePath")
        execAdbShellCmd("run-as $packageName rm -rf /data/data/$packageName/code_cache/.overlay/$filePath")
    }

    private fun execAdbShellCmd(cmd: String): String {
        return adb.execAdbShellCmd(cmd)
    }
}