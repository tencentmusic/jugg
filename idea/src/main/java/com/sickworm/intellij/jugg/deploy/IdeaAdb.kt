package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.google.common.base.Charsets
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import java.util.concurrent.TimeUnit

import com.sickworm.intellij.jugg.deploy.AdbCmdHelper as AdbCmdHelperClass

class IdeaAdb(
    private val device: IDevice,
    private val ideaLogger: Logger,
) : IAdb {

    private val logger = LogWrapper(ideaLogger).also {
        it.alwaysLogAsDebug(true)
        it.allowVerbose(true)
    }
    private val adb = AdbClient(device, this.logger)

    override fun execAdbShellCmd(cmd: String): String {
        try {
            logger.info("%s", "adb in: adb shell $cmd")
            val response = adb.shell(
                cmd.split(" ").toTypedArray(),
                null, 5L, TimeUnit.MINUTES)
            if (response.isNotEmpty()) {
                val extraMsg = String(response, Charsets.UTF_8).trim { it <= ' ' }
                val logMsg = if (extraMsg.length > 200) {
                    extraMsg.substringBefore('\n') + "...(additional lines ${extraMsg.lines().size - 1})"
                } else {
                    extraMsg
                }
                logger.info("%s", "adb out: $logMsg")
                return extraMsg
            }
            return ""
        } catch (e: Exception) {
            logger.error(e, "invoke execAdbShellCmd failed")
            throw JuggException.invokeAdbFailed2(cmd, e)
        }
    }

    override fun getDefaultLaunchActivity(apkFile: File): String {
        return ApkReader(apkFile, ideaLogger).getDefaultActivity()!!
    }
}

@Suppress("FunctionName")
fun AdbCmdHelper(device: IDevice, logger: Logger): AdbCmdHelperClass {
    return AdbCmdHelperClass(device, logger)
}