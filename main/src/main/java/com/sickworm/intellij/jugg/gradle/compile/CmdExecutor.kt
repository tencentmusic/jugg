package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.JuggException
import kotlinx.coroutines.*
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.Charset


class CmdExecutor(
    private val logger: Logger,
    /** only use to print to other place e.g. running terminal */
    var terminalOutputListener: IGradleCompileClient.TerminalOutputListener = IGradleCompileClient.TerminalOutputListener.IDLE,
    private val debugLogFilter: ((String) -> Boolean)? = null,
) {

    @Volatile
    private var currentRunningProcess: Process? = null

    fun invoke(command: ISshCommand, envArray: List<String>? = null): Int {
        val printSafeCommand = command.getPrintSafeCommand(isNeedSetChineseLanguage = false, isWindows = isWindows)
        logger.debug("CmdExecutor invoke command: $printSafeCommand")

        val commandString = command.getCommand(isNeedSetChineseLanguage = false, isWindows = isWindows)
        val sshLoginPassword = if (command is RsyncCommand) command.password else null
        val commands = if (sshLoginPassword != null) {
            if (isWindows) {
                throw JuggException.rSyncNotSupportsWindows()
            }
            arrayOf("expect", "-c", """
                set timeout 36000
                spawn /bin/bash -c "${commandString.replace("\"", "\\\"")}"
                expect {
                    "*yes/no" { send "yes\r"; exp_continue }
                    "*assword:" { send "$sshLoginPassword\r"; exp_continue }
                }
            """.trimIndent())
        } else if (isWindows) {
            arrayOf("cmd.exe", "/c", commandString)
        } else {
            arrayOf("/bin/bash", "-c", commandString)
        }

        val process = Runtime.getRuntime().exec(
            commands,
            envArray?.toTypedArray(),
        )
        currentRunningProcess = process

        val charset = if (isWindows && Charset.isSupported("GBK")) {
            Charset.forName("GBK")
        } else {
            Charsets.UTF_8
        }
        val commander = PrintStream(process.outputStream, false)
        val errorPrintThread = object : Thread() {
            override fun run() {
                val reader = process.errorStream.bufferedReader(charset)
                while (!isInterrupted) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            if (line.isNotEmpty()) {
                                if (command.isCanOutput(line, isError = true)) {
                                    printToStreamError(line)
                                }
                            }
                        }
                    } catch (e: IOException) {
                        // java.io.IOException: Stream closed
                        break
                    }
                }
            }
        }
        errorPrintThread.start()

        var isShouldBreak = false
        var timeOutJob: Job? = null
        val reader = process.inputStream.bufferedReader(charset)
        var result: Int = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
        while (!isShouldBreak) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    if (line.isNotEmpty()) {
                        if (command.isCanOutput(line, isError = false)) {
                            printToStream(line)
                        }
                    }
                    val output = command.getInput(line)
                    if (output != null) {
                        logger.debug("output: $output")
                        commander.println(output)
                        commander.flush()
                    }
                    val currentResult = command.hasFinishWithResult(line)
                    if (currentResult != null) {
                        result = currentResult
                        break
                    }
                }
            } catch (e: IOException) {
                // java.io.IOException: Stream closed
                logger.debug("get IOException $e")
                break
            }

            if (!process.isAlive) {
                // process is exited, maybe it's canceled by user
                // but process may not print all out, wait for max 200ms
                if (timeOutJob == null) {
                    printToStream("[Jugg] exit-status: " + process.exitValue())
                    timeOutJob = CoroutineScope(Dispatchers.IO).launch {
                        delay(200)
                        logger.debug("Command result not received and process is exited for 200ms, break loop")
                        reader.close()
                        isShouldBreak = true
                    }
                }
            }
        }
        timeOutJob?.cancel()
        process.waitFor()
        errorPrintThread.interrupt()
        currentRunningProcess = null

        return result
    }

    fun release() {
        currentRunningProcess?.destroy()
        currentRunningProcess = null
    }

    private fun printToStream(line: String) {
        if (line.startsWith("rsync")) {
            if (line.contains("fail") || line.contains("error")) {
                printToStreamError(line)
                return
            }
        }
        if (line.startsWith("spawn /bin/bash")) {
            // filter lines that contains password
            logger.debug("spawn /bin/bash ********(secret)")
            return
        }
        if (debugLogFilter == null || debugLogFilter.invoke(line)) {
            logger.debug(line)
        }
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.warn(line, e)
    }
}