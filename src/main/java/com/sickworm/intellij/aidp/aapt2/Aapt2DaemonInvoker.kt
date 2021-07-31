package com.sickworm.intellij.aidp.aapt2

import com.sickworm.intellij.aidp.AidpInternalException
import com.sickworm.intellij.aidp.isWindows
import java.io.*
import java.util.*


class Aapt2DaemonInvoker(val androidBuildTools: File) {

    private var process: Process? = null
    private var outputReader: OutputReader? = null

    @Synchronized
    private fun init() {
        val aapt2Name = if (isWindows) "aapt2.exe" else "aapt2"
        val aapt2Cmd = "${androidBuildTools.absolutePath}/$aapt2Name"
        val process = Runtime.getRuntime().exec("$aapt2Cmd daemon")
        val output = readOutput(process!!.inputStream, 1)
        if (output != "Ready\n") {
            throw AidpInternalException.startAapt2DaemonFailed()
        }
        this.process = process
        outputReader = OutputReader(process.inputStream, process.errorStream)
    }

    fun invoke(params: String): Aapt2Result {
        if (process == null) {
            init()
        }
        println("params: $params")
        val process = process!!
        process.outputStream.write("${params.replace(" ", "\n")}\n\n".toByteArray()) // double \n for commands end
        process.outputStream.flush()

        return outputReader!!.read()
    }

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

    private class OutputReader(private val inputStream: InputStream, private val errorStream: InputStream) {

        @Volatile
        private var outputBuilder = StringBuilder()

        init {
            Thread {
                readOutput(inputStream)
            }.start()
        }

        fun read(): Aapt2Result {
            // FIXME can not get get fucking output without delay
            Thread.sleep(500)
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
                println("output: $line")
                readLine++
            }
            println("output lines: $readLine")
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
                println("error: $line")
            }
            println("error out: $readLine")
            return stringBuilder.toString()
        }
    }
}