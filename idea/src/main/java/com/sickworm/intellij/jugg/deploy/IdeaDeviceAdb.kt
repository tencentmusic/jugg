package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.google.common.base.Charsets
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import java.util.concurrent.TimeUnit

class IdeaDeviceAdb(
    private val device: IDevice,
    ideaLoggerArg: Logger,
) : IDeviceAdb {

    private val ideaLogger = ideaLoggerArg.getInstance("IdeaDeviceAdb")

    override val deviceName: String
        get() = device.name

    private val logger = LogWrapper(ideaLogger).also {
        it.alwaysLogAsDebug(true)
        it.allowVerbose(true)
    }
    private val adb = AdbClient(device, this.logger)

    @Synchronized
    override fun execAdbShellCmd(cmd: String): String {
        try {
            val cmdList = cmd.splitIgnoringQuotes()
            logger.info("%s", "adb in:  adb shell $cmd")
            logger.info("%s", "adb in:  cmd splits to : $cmdList")
            val response = adb.shell(
                cmdList.toTypedArray(),
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

    @Synchronized
    override fun push(from: File, to: String): Boolean {
        return try {
            adb.push(from.path, to)
            true
        } catch (e: Exception) {
            logger.error(e, "adb push failed, from: $from, to: $to")
            false
        }
    }

    override fun getDefaultLaunchActivity(apkFile: File): String {
        return ApkReader(apkFile, ideaLogger).getDefaultActivity()!!
    }

    companion object {

        /**
         * Split the string into a list of strings, handling quotes and escapes.
         *
         * input: run-as com.example.myapplication "mkdir abc && ls abc"
         * output: [run-as, com.example.myapplication, mkdir abc && ls abc]
         */
        private fun String.splitIgnoringQuotes(): List<String> {
            val result = mutableListOf<String>()
            val sb = StringBuilder()
            var isInQuotes = false
            var isLastCharEscaping = false

            for (currentChar in this) {
                if (currentChar == '\"' || currentChar == '\'') {
                    if (!isLastCharEscaping) {
                        isInQuotes = !isInQuotes
                    }
                }

                if (currentChar == ' ' && !isInQuotes) {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                } else {
                    sb.append(currentChar)
                }

                if (currentChar == '\\') {
                    isLastCharEscaping = !isLastCharEscaping
                }
            }

            if (sb.isNotEmpty()) {
                if (sb.length >= 2 && sb.startsWith("\"") && sb.endsWith("\"")) {
                    val striped = sb.substring(1, sb.length - 1)
                    result.add(striped)
                } else {
                    result.add(sb.toString())
                }
            }

            return result
        }
    }
}

fun AdbCmdHelper(device: IDevice, logger: Logger): AdbCmdHelper {
    return AdbCmdHelper(IdeaDeviceAdb(device, logger), logger)
}