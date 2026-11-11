package com.sickworm.intellij.jugg.aapt2

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isLinux
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggException
import java.io.*
import java.util.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Aapt2DaemonInvoker owns the embedded `aapt2 daemon` process lifecycle and command execution.
 * Collaboration: Initializes platform binary paths through [getEmbeddedAapt2], delegates stream parsing to [OutputReader], and returns [Aapt2Result] to APK/resource parsing callers.
 * Data Contract: [invoke] writes each argument as one daemon protocol line and lazily initializes the process; [init] requires the first daemon line to be `Ready` and throws [JuggInternalException.startAapt2DaemonFailed] otherwise.
 */
class Aapt2DaemonInvoker(
    parentLogger: Logger,
    private val aapt2: File = getEmbeddedAapt2(),
    ) {

    private val logger: Logger = parentLogger.getInstance("Aapt2DaemonInvoker")

    private var process: Process? = null
    private var outputReader: OutputReader? = null

    @Synchronized
    private fun init() {
        logger.debug("start aapt2 daemon")
        val process = try {
            Runtime.getRuntime().exec("$aapt2 daemon")
        } catch (e: IOException) {
            if (!isWindows || e.message?.contains("CreateProcess error=32,") != true) throw e
            logger.debug("Windows temporarily locked the aapt2 executable; retrying daemon startup", e)
            Thread.sleep(1000)
            Runtime.getRuntime().exec("$aapt2 daemon")
        }
        val output = readLine(process!!.inputStream)
        if (output != "Ready") {
            throw JuggInternalException.startAapt2DaemonFailed()
        }
        this.process = process
        outputReader = OutputReader(process.inputStream, process.errorStream, logger)
        outputReader?.init()
    }

    @Synchronized
    fun isAlive(): Boolean {
        return process?.isAlive == true
    }

    @Synchronized
    fun invoke(params: List<String>): Aapt2Result {
        if (process?.isAlive != true) {
            init()
        }
        logger.debug("aapt2 daemon command: ${params.joinToString(" ")}")
        val process = process!!
        process.outputStream.write("${params.joinToString("\n")}\n\n".toByteArray()) // double \n for commands end
        process.outputStream.flush()

        return outputReader?.read() ?: Aapt2Result("", "")
    }

    @Synchronized
    fun release() {
        logger.debug("exit aapt2 daemon")
        process?.destroy()
        outputReader?.release()
    }

    private fun readLine(stream: InputStream): String {
        val sc = Scanner(stream)

        return if (sc.hasNextLine()) {
            sc.nextLine()
        } else {
            "error_no_next_line"
        }
    }

    /**
     * OutputReader collects daemon stdout/stderr and assembles one [Aapt2Result] per command cycle.
     * Data Contract: [read] snapshots and clears [outputBuilder], and stderr scanning stops when `Done` is read.
     */
    private class OutputReader(
        private val inputStream: InputStream,
        private val errorStream: InputStream,
        private val logger: Logger
        ) {

        @Volatile
        private var outputBuilder = StringBuilder()

        private var readOutputThread = Thread {
            readOutput(inputStream)
        }

        fun init() {
            readOutputThread.start()
        }

        fun release() {
            readOutputThread.interrupt()
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
            while (sc.hasNextLine() && !Thread.interrupted()) {
                try {
                    val line = sc.nextLine()
                    outputBuilder.appendLine(line)
                    logger.debug("std output: $line")
                    readLine++
                } catch (e: Exception) {
                    logger.debug("readOutputThread interrupted")
                    break
                }
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
        private val preparedAapt2: File by lazy {
            val resourcePath = when {
                isMac -> "/tools/darwin/aapt2-inclink"
                isLinux -> "/tools/linux/aapt2-inclink"
                isWindows -> "/tools/windows/aapt2-inclink.exe"
                else -> throw JuggException.unsupportedOs()
            }
            val target = JuggGlobalPathManager.resourceFile(resourcePath)
            Files.createDirectories(target.parentFile.toPath())
            val temporary = Files.createTempFile(target.parentFile.toPath(), "aapt2-inclink-", ".tmp")
            try {
                val resource = Aapt2DaemonInvoker::class.java.getResourceAsStream(resourcePath)
                    ?: throw IOException("Missing embedded aapt2 resource: $resourcePath")
                resource.use { input ->
                    Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
                }
                if (!temporary.toFile().setExecutable(true)) {
                    throw IOException("Failed to make embedded aapt2 executable: $temporary")
                }
                // Replace the directory entry without truncating a running Unix executable.
                Files.move(temporary, target.toPath(), REPLACE_EXISTING)
                target
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        fun getEmbeddedAapt2(): File = preparedAapt2
    }
}
