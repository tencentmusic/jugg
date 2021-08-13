package com.sickworm.intellij.aidp.aapt2

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.AidpInternalException
import com.sickworm.intellij.aidp.isWindows
import java.io.*
import java.lang.IllegalStateException
import java.util.*

/**
 * invoke aapt2 with custom build
 */
class Aapt2DaemonInvoker(
    private val logger: Logger,
    private val aapt2: File = getEmbeddedAapt2(),
    ) {

    private var process: Process? = null
    private var outputReader: OutputReader? = null

    @Synchronized
    private fun init() {
        val process = Runtime.getRuntime().exec("$aapt2 daemon")
        val output = readOutput(process!!.inputStream, 1)
        if (output != "Ready\n") {
            throw AidpInternalException.startAapt2DaemonFailed()
        }
        this.process = process
        outputReader = OutputReader(process.inputStream, process.errorStream, logger)
    }

    fun invoke(params: String): Aapt2Result {
        if (process == null) {
            init()
        }
        logger.debug("aapt2 command: aapt2 $params")
        val process = process!!
        process.outputStream.write("${params.replace(" ", "\n")}\n\n".toByteArray()) // double \n for commands end
        process.outputStream.flush()

        return outputReader!!.read()
    }

    // TODO remove
    private fun readOutput(stream: InputStream, limitLine: Int = Int.MAX_VALUE): String {
        val stringBuilder = StringBuilder()
        val sc = Scanner(stream)

        var readLine = 0
        while ((readLine++ < limitLine) && sc.hasNextLine()) {
            val line = sc.nextLine()
            stringBuilder.appendLine(line)
        }
        return stringBuilder.toString()
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
                logger.debug("output: $line")
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
                stringBuilder.appendLine(line)
                readLine++
                logger.warn("error: $line")
            }
            logger.debug("error out: $readLine")
            return stringBuilder.toString()
        }
    }

    companion object {
        fun getEmbeddedAapt2(): File {
            // TODO change to getResources
            if (isWindows) {
                throw IllegalStateException("embedded aapt2 not support windows yet")
            }
            return File("src/main/resources/libs/aapt2")
        }
    }
}