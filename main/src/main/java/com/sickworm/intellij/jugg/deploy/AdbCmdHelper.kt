package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.google.common.base.Charsets
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import java.util.concurrent.TimeUnit

class AdbCmdHelper(
    private val device: IDevice,
    private val ideaLogger: Logger,
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

    fun installApp(apkFile: File): Pair<Boolean, String> {
        @Suppress("INACCESSIBLE_TYPE")
        val result: Any = adb.install(listOf(apkFile.absolutePath), listOf("-r"), true)
        val statusField = result::class.java.getField("status")
        statusField.isAccessible = true
        val statusString = statusField.get(result).toString()

        val reasonField = result::class.java.getField("reason")
        reasonField.isAccessible = true
        val reasonString: String? = reasonField.get(result) as? String

        return if (statusString == "OK") {
            Pair(true, "")
        } else {
            val reasonStringOrEmpty = if (reasonString != null) {
                "($reasonString)"
            } else {
                ""
            }
            Pair(false, "$statusString$reasonStringOrEmpty")
        }
    }

    fun startDefaultApp(packageName: String, apks: List<ApkInfo>, isRestart: Boolean = true) {
        val apkFile = apks.first().files.first().apkFile
        val launchedActivity = ApkReader(apkFile, ideaLogger).getDefaultActivity()!!
        startApp(packageName, launchedActivity, isRestart)
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