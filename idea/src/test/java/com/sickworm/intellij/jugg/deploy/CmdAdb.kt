package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

class CmdAdb(loggerArg: Logger): IDeviceAdb {

    private val logger = loggerArg.getInstance("CmdAdb")

    override val displayName: String = "mock_device"

    override val api: Int = 30

    override fun execAdbShellCmd(cmd: String): String {
        logger.debug("adb in:  adb shell $cmd") // two spaces to align adb out
        val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", "adb shell \"$cmd\""))
        val normalOutput = String(process.inputStream.readAllBytes())
        val errorOutput = String(process.errorStream.readAllBytes())
        process.waitFor()
        var result = normalOutput
        if (errorOutput.isNotEmpty()) {
            if (normalOutput.trim().isNotEmpty()) {
                result += "\n"
            }
            result += errorOutput
        }
        logger.debug("adb out: $result")
        return result
    }

    override fun push(from: File, to: String): Boolean {
        val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", "adb push $from $to"))
        process.waitFor()
        return true
    }

    fun install(apkFile: File) {
        val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", "adb install ${apkFile.path}"))
        process.waitFor()
    }

    override fun getDefaultLaunchActivity(apkFile: File): String {
        return ApkReader(apkFile, logger).getDefaultActivity()!!
    }

    override fun getArch(packageName: String): String {
        return "ARCH_64_BIT"
    }
}