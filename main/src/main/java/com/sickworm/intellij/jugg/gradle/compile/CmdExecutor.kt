package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.JuggException
import java.io.IOException
import java.io.PrintStream


class CmdExecutor(
    var terminalOutputListener: IGradleCompileClient.TerminalOutputListener,
    private val logger: Logger,
) {

    @Volatile
    private var currentRunningProcess: Process? = null

    fun invoke(command: ISshCommand, envArray: List<String>? = null, sshLoginPassword: String? = null): Int {
        val commandString = command.getCommand(isNeedSetChineseLanguage = false, isWindows = isWindows)
        logger.debug("CmdExecutor invoke command: $commandString")
        val commands = if (sshLoginPassword != null) {
            if (isWindows) {
                throw JuggException.rSyncNotSupportsWindows()
            }
            arrayOf("expect", "-c", """
                spawn /bin/bash -c "${commandString.replace("\"", "\\\"")}"
                expect "assword:"
                send "$sshLoginPassword\r"
                expect eof
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

        val commander = PrintStream(process.outputStream, false)
        val errorPrintThread = object : Thread() {
            override fun run() {
                val reader = process.errorStream.bufferedReader(Charsets.UTF_8)
                while (!isInterrupted) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            if (line.isNotEmpty()) {
                                printToStreamError(line)
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

        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var result: Int
        while (true) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    if (line.isNotEmpty()) {
                        printToStream(line)
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
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }

            if (!process.isAlive) {
                printToStream("[Jugg] exit-status: " + process.exitValue())
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }
        }
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
            } else {
                logger.debug(line)
            }
        }
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.warn(line, e)
    }
}