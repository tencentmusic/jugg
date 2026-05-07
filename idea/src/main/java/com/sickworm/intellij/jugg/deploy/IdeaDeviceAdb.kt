package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.google.common.base.Charsets
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File
import java.io.InterruptedIOException
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

    override val serial: String
        get() = device.serialNumber

    override val isOnline: Boolean
        get() = device.isOnline

    private val loggerWrapper = LogWrapper(logger).also {
        it.alwaysLogAsDebug(true)
        it.allowVerbose(true)
    }
    private val adb = AdbClient(device, this.loggerWrapper)

    override fun execAdbShellCmd(cmd: String): String {
        synchronized(IdeaDeviceAdb::class.java) {
            return execAdbShellCmd(cmd, retryCount = 0)
        }
    }

    override fun execAdbShellScript(cmd: String): String {
        synchronized(IdeaDeviceAdb::class.java) {
            logger.debug("adb script in: sh -c '...'")
            // Escape single quotes and wrap with sh -c to support shell syntax (pipes, background processes, variables, etc.).
            val escaped = cmd.replace("'", "'\\''")
            return execAdbShellCmd("sh -c '$escaped'", retryCount = 0)
        }
    }

    /**
     * Runs [cmd] and delivers each output line to [lineConsumer] as it arrives.
     * Respects [cancelSignal]: when it returns true the receiver is closed promptly.
     * Uses ddmlib [IDevice.executeShellCommand] with a custom [IShellOutputReceiver].
     */
    override fun execAdbShellCmdStreaming(
        cmd: String,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): Int {
        logger.info("adb streaming in: $cmd")
        var exitCode = 0
        val receiver = object : com.android.ddmlib.IShellOutputReceiver {
            private val buffer = StringBuilder()

            override fun addOutput(data: ByteArray, offset: Int, length: Int) {
                val chunk = String(data, offset, length, Charsets.UTF_8)
                buffer.append(chunk)
                // emit complete lines
                var newlineIdx = buffer.indexOf('\n')
                while (newlineIdx >= 0) {
                    val line = buffer.substring(0, newlineIdx).trimEnd('\r')
                    lineConsumer(line)
                    buffer.delete(0, newlineIdx + 1)
                    newlineIdx = buffer.indexOf('\n')
                }
            }

            override fun flush() {
                // emit any remaining partial line
                if (buffer.isNotEmpty()) {
                    lineConsumer(buffer.toString().trimEnd('\r'))
                    buffer.clear()
                }
            }

            override fun isCancelled(): Boolean = cancelSignal()
        }

        try {
            // Use a long timeout (1 hour) – the test suite itself governs duration.
            device.executeShellCommand(cmd, receiver, 3600L, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (isExpectedStreamingStop(e, cancelSignal)) {
                logger.debug("execAdbShellCmdStreaming stopped: $cmd", e)
            } else {
                logger.warn("execAdbShellCmdStreaming failed: $cmd", e)
            }
            exitCode = -1
        }
        return exitCode
    }

    private fun execAdbShellCmd(cmd: String, retryCount: Int): String {
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
            logger.debug("adb out: (empty)")
            return ""
        } catch (e: Exception) {
            logger.debug("invoke execAdbShellCmd failed, retry count $retryCount ", e)
            // java.nio.channels.InterruptedByTimeoutException
            // com.android.ddmlib.TimeoutException
            val timeoutException = listOf("InterruptedByTimeoutException", "TimeoutException")
            // java.net.ConnectException
            // java.net.SocketException
            val connectException = listOf("SocketException", "ConnectException")

            val exceptionName = e::class.java.simpleName
            if (exceptionName in timeoutException) {
                if (retryCount == 0) {
                    logger.warn("Got ADB timeout, retry after restart adb.")
                    killAdbProcess()
                    Thread.sleep(2000)
                    return execAdbShellCmd(cmd, retryCount = 1)
                }
            } else if (exceptionName in connectException) {
                logger.debug("ADB can not connect, try retry")
                if (retryCount == 0) {
                    killAdbProcess()
                }
                if (retryCount < 8) {
                    Thread.sleep(2000)
                    return execAdbShellCmd(cmd, retryCount = retryCount + 1)
                }
            }
            logger.warn("invoke execAdbShellCmd failed, fallback to adb cli", e)
            return execAdbShellCmdByCli(cmd)
        }
    }

    private fun execAdbShellCmdByCli(cmd: String): String {
        val adbBin = AdbCmdHelper.findAdbExecutablePath()
        val process = Runtime.getRuntime().exec(arrayOf(adbBin, "-s", serial, "shell", cmd))
        val normalOutput = String(process.inputStream.readAllBytes())
        val errorOutput = String(process.errorStream.readAllBytes())
        process.waitFor()
        val merged = buildString {
            append(normalOutput)
            if (errorOutput.isNotEmpty()) {
                if (normalOutput.isNotEmpty()) {
                    append("\n")
                }
                append(errorOutput)
            }
        }.trim()
        logger.debug("adb(cli) out: $merged")
        return merged
    }

    private fun killAdbProcess() {
        logger.debug("killAdbProcess in")
        val cmdString = if (isWindows) {
            "taskkill /F /IM adb.exe"
        } else {
            "killall adb"
        }
        val cmd = SimpleSshCommand(cmdString, logger.getInstance("IdeaDeviceAdb_CMD"))
        val exitCode = CmdExecutor(logger, isLogAllDebug = true).invoke(cmd)
        logger.debug("killAdbProcess exitCode: $exitCode")
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

    @Synchronized
    override fun pull(from: String, to: File): Boolean {
        return try {
            to.parentFile?.mkdirs()
            device.pullFile(from, to.path)
            true
        } catch (e: Exception) {
            logger.warn("adb pull by ddmlib failed, fallback to adb cli. from: $from, to: $to", e)
            try {
                val adbBin = AdbCmdHelper.findAdbExecutablePath()
                val process = Runtime.getRuntime().exec(arrayOf(adbBin, "-s", serial, "pull", from, to.path))
                process.waitFor()
                process.exitValue() == 0
            } catch (e2: Exception) {
                logger.warn("adb pull fallback failed, from: $from, to: $to", e2)
                false
            }
        }
    }

    override fun getDefaultLaunchActivity(apkFile: File): String? {
        return ApkReader(apkFile, logger).getDefaultActivity()
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

internal fun isExpectedStreamingStop(error: Throwable, cancelSignal: () -> Boolean): Boolean {
    if (runCatching { cancelSignal() }.getOrDefault(false)) {
        return true
    }
    return error.causeChain().any { cause ->
        cause is InterruptedException ||
            cause is InterruptedIOException ||
            cause.message?.contains("Operation interrupted", ignoreCase = true) == true
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    while (current != null) {
        yield(current)
        current = current.cause
    }
}

fun AdbCmdHelper(device: IDevice, logger: Logger): AdbCmdHelper {
    return AdbCmdHelper(IdeaDeviceAdb(device, logger), logger)
}
