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

    private val logger = ideaLoggerArg.getInstance("IdeaDeviceAdb")

    override val displayName: String
        get() = device.getProperty("ro.product.manufacturer") + " " + device.getProperty("ro.product.model")

    override val api: Int
        get() = device.version.apiLevel

    private val loggerWrapper = LogWrapper(logger).also {
        it.alwaysLogAsDebug(true)
        it.allowVerbose(true)
    }
    private val adb = AdbClient(device, this.loggerWrapper)

    @Synchronized
    override fun execAdbShellCmd(cmd: String): String {
        try {
            val cmdList = cmd.splitIgnoringQuotes()
            logger.debug("adb in:  adb shell $cmd")
            logger.debug("adb in:  cmd splits to : $cmdList")
            val response = adb.shell(
                cmdList.toTypedArray(),
                null, 5L, TimeUnit.SECONDS)
            if (response.isNotEmpty()) {
                val extraMsg = String(response, Charsets.UTF_8).trim { it <= ' ' }
                val logMsg = if (extraMsg.length > 200) {
                    extraMsg.substringBefore('\n') + "...(additional lines ${extraMsg.lines().size - 1})"
                } else {
                    extraMsg
                }
                logger.debug("adb out: $logMsg")
                return extraMsg
            }
            return ""
        } catch (e: Exception) {
            logger.debug("invoke execAdbShellCmd failed", e)
            throw JuggException.invokeAdbFailed2(cmd, e)
        }
    }

    @Synchronized
    override fun push(from: File, to: String): Boolean {
        return try {
            adb.push(from.path, to)
            true
        } catch (e: Exception) {
            logger.warn("adb push failed, from: $from, to: $to", e)
            false
        }
    }

    override fun getDefaultLaunchActivity(apkFile: File): String {
        return ApkReader(apkFile, logger).getDefaultActivity()!!
    }

    override fun getArch(packageName: String): String {
        try {
            val adbClient = AdbClient(device, loggerWrapper)
            val pids = adbClient.getPids(packageName)
            val arch = adbClient.getArch(pids)
            return arch.name
        } catch (e: Exception) {
            // e.g. HarmonyOS. Let outside decide which arch to use
            logger.debug("getArch failed $e")
            return "ARCH_UNKNOWN"
        }
    }

    override fun getProperty(name: String): String? {
        return device.getProperty(name)
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