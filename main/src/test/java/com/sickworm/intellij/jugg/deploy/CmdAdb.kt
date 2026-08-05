package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

class CmdAdb(
    loggerArg: Logger,
    override val serial: String = resolveDeviceSerial(),
): IDeviceAdb {

    private val logger = loggerArg.getInstance("CmdAdb")

    override val displayName: String = "mock_device"

    override val api: Int = 30

    override val isOnline: Boolean = true

    override fun execAdbShellCmd(cmd: String): String {
        logger.debug("adb in:  adb -s $serial shell $cmd") // two spaces to align adb out
        val process = ProcessBuilder("adb", "-s", serial, "shell", cmd)
            .redirectErrorStream(true)
            .start()
        val result = String(process.inputStream.readAllBytes())
        process.waitFor()
        logger.debug("adb out: $result")
        return result
    }

    override fun push(from: File, to: String): Boolean {
        return ProcessBuilder("adb", "-s", serial, "push", from.path, to).start().waitFor() == 0
    }

    override fun pull(from: String, to: File): Boolean {
        to.parentFile?.mkdirs()
        return ProcessBuilder("adb", "-s", serial, "pull", from, to.path).start().waitFor() == 0
    }

    fun install(apkFile: File) {
        val process = ProcessBuilder("adb", "-s", serial, "install", apkFile.path).start()
        process.waitFor()
    }

    override fun getDefaultLaunchActivity(apkFile: File): String {
        return ApkReader(apkFile, logger).getDefaultActivity()!!
    }

    override fun getArch(packageName: String): String {
        return "ARCH_64_BIT"
    }

    override fun getProperty(name: String): String? {
        return null
    }

    companion object {
        private fun resolveDeviceSerial(): String {
            System.getenv("JUGG_TEST_DEVICE_SERIAL")?.let { return it }
            System.getenv("ANDROID_SERIAL")?.let { return it }
            val process = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
            val output = String(process.inputStream.readAllBytes())
            process.waitFor()
            return output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }
                ?.substringBefore('\t')
                ?: throw IllegalStateException("No online Android device found")
        }
    }
}
