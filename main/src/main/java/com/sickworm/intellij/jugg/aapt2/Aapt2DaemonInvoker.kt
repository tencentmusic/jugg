package com.sickworm.intellij.jugg.aapt2

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.compiler.copyResource
import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isLinux
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.JuggException
import java.io.*
import java.util.*

/**
 * invoke aapt2-inclink with custom build
 */
class Aapt2DaemonInvoker(
    private val logger: Logger,
    private val aapt2: File = getEmbeddedAapt2(),
    ) {

    private var process: Process? = null
    private var outputReader: OutputReader? = null

    @Synchronized
    private fun init() {
        logger.debug("start aapt2 daemon")
        val process = Runtime.getRuntime().exec("$aapt2 daemon")
        val output = readLine(process!!.inputStream)
        if (output != "Ready") {
            throw JuggInternalException.startAapt2DaemonFailed()
        }
        this.process = process
        outputReader = OutputReader(process.inputStream, process.errorStream, logger)
    }

    fun invoke(params: String): Aapt2Result {
        if (process?.isAlive != true) {
            init()
        }
        logger.debug("aapt2 daemon command: $params")
        val process = process!!
        process.outputStream.write("${params.replace(" ", "\n")}\n\n".toByteArray()) // double \n for commands end
        process.outputStream.flush()

        return outputReader!!.read()
    }

    fun release() {
        logger.debug("exit aapt2 daemon")
        process?.destroy()
    }

    private fun readLine(stream: InputStream): String {
        val sc = Scanner(stream)

        return if (sc.hasNextLine()) {
            sc.nextLine()
        } else {
            "error_no_next_line"
        }
    }

    private class OutputReader(
        private val inputStream: InputStream,
        private val errorStream: InputStream,
        private val logger: Logger
        ) {

        @Volatile
        private var outputBuilder = StringBuilder()

        init {
            Thread {
                readOutput(inputStream)
            }.start()
        }

        fun read(): Aapt2Result {
            val error = readError(errorStream)
            val output = outputBuilder.toString()
            outputBuilder.clear()
            return Aapt2Result(output, error)
        }

        private fun readOutput(stream: InputStream) {
            val sc = Scanner(stream)

            var readLine = 0
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                outputBuilder.appendLine(line)
                logger.debug("std output: $line")
                readLine++
            }
            logger.debug("output lines: $readLine")
        }

        private fun readError(stream: InputStream): String {
            val stringBuilder = StringBuilder()
            val sc = Scanner(stream)

            var readLine = 0
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                if (line == "Done") break
                if (line.contains("warn: multiple substitutions specified in non-positional format")) {
                    // ignore
                    continue
                }
                if (line.contains("but no such path exists")) {
                    // outputs when loadTable using an apk with only manifest and arsc
                    // ignore
                    continue
                }
                stringBuilder.appendLine(line)
                readLine++

                if (line.contains("error: ")) {
                    logger.warn("output: $line")
                } else {
                    // warn: / note:
                    logger.debug("output: $line")
                }
            }
            logger.debug("aapt2 invoke finished")
            return stringBuilder.toString()
        }
    }

    companion object {
        fun getEmbeddedAapt2(): File {
            val version = "2.19.7"
            return if (isMac) {
                copyResource("/tools/darwin/aapt2-inclink-$version")
            } else if (isLinux) {
                copyResource("/tools/linux/aapt2-inclink-$version")
            } else if (isWindows) {
                copyResource("/tools/windows/aapt2-inclink-$version.exe")
            } else {
                throw JuggException.unsupportedOs()
            }
        }
    }
}