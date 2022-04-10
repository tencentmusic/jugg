package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileError
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.Result
import java.io.File
import java.io.OutputStream
import java.io.PrintStream

class KotlinCompilerOutputParser(
    private val files: List<CompileFile>,
    private val logger: Logger
) {

    private val outputStream = object : OutputStream() {
        override fun write(b: Int) {
            onInput(b)
        }
    }
    val printStream = PrintStream(outputStream)


    val line = StringBuilder()
    private fun onInput(b: Int) {
        if (b == '\n'.toInt()) {
            onNewLine(line.toString())
            line.clear()
        } else {
            line.append(b.toChar())
        }
    }

    val outputs: List<File>
        get() = innerOutputs
    val results: List<Result<CompileFile, CompileError>>
        get() = files.map {
            val errorDetails = innerErrors[it]
            if (errorDetails != null) {
                Result.failure(CompileError(it, errorDetails))
            } else {
                Result.success(it)
            }
        }

    private val innerErrors = mutableMapOf<CompileFile, MutableList<Pair<Long, String>>>()
    private val innerOutputs = mutableListOf<File>()

    val currentMessage = StringBuilder()
    private var currentMessageType: MessageType = MessageType.LOGGING

    private val newLineRegex = Regex("(.*):?(logging|warning|error|output):(.*)")

    private fun onNewLine(line: String) {
        val contents = newLineRegex.find(line)?.groups
        val tag = contents?.get(2)?.value

        val newMessageType = MessageType.getByTag(tag)
        if (newMessageType != null && currentMessage.isNotEmpty()) {
            handleCurrentMessage()
            currentMessageType = newMessageType
        }

        if (currentMessage.isNotEmpty()) {
            currentMessage.appendLine()
        }
        currentMessage.append(line)
    }

    private fun handleCurrentMessage() {
        val message = currentMessage.toString()
        when (currentMessageType) {
            MessageType.LOGGING -> {
                logger.debug(message)
            }
            MessageType.WARNING -> {
                logger.debug(message)
            }
            MessageType.ERROR -> {
                logger.warn(message)
                parseErrorMessage(message)
            }
            MessageType.OUTPUT -> {
                logger.debug(message)
                parseOutputMessage(message)
            }
        }
        currentMessage.clear()
    }

    private val errorRegex = Regex("(.*):(.*):(.*): error: (.*)")

    private fun parseErrorMessage(message: String) {
        // e.g.
        // src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/sickworm/jugg/demo/ #soft wrap
        // testcase/CaseKtSmartCast.kt:9:26: error: smart cast to 'MutableList<String>' is impossible, #soft wrap
        // because 'class2.dataList' is a public API property declared in different module
        val contents = errorRegex.find(message)
        val filePath = contents?.groups?.get(1)?.value?: ""
        val line = contents?.groups?.get(2)?.value?.toLongOrNull()?: -1L
        val file = files.find { it.file.absolutePath.endsWith(filePath) }
        if (file == null) {
            logger.debug("failed to parse error message: $message")
            return
        }

        if (!innerErrors.containsKey(file)) {
            innerErrors[file] = mutableListOf()
        }
        innerErrors[file]?.add(line to message)
    }

    private fun parseOutputMessage(message: String) {
        // e.g.
        // output: output:
        // /Users/wormchen/IdeaProjects/jugg/src/test/assets/android/MyApplicationIntellij/app/ #soft wrap
        // build/tmp/kotlin-classes/debug/com/sickworm/jugg/demo/testcase/CaseKtSmartCast.class
        // Sources:
        // /Users/wormchen/IdeaProjects/jugg/src/test/assets/android/MyApplicationIntellij/app/ #soft wrap
        // src/main/java/com/sickworm/jugg/demo/testcase/CaseKtSmartCast.kt

        val contents = message.split("\n")
        if (contents.isEmpty()) {
            logger.debug("Failed to parse output message: $message")
            return
        }

        val outputFiles = mutableListOf<File>()
        // first line is "output: output:", ignore
        for (i in 1 until contents.size) {
            val filePath = contents[i]
            if (filePath == "Sources:") {
                // reaches end
                break
            }

            val file = File(filePath)
            if (file.exists()) {
                outputFiles.add(file)
            } else {
                logger.debug("Failed to parse output message, file not exists: $file")
            }
        }

        if (outputFiles.isEmpty()) {
            logger.debug("Failed to parse output message: $message")
            return
        }

        innerOutputs.addAll(outputFiles)
    }

    fun flush() {
        handleCurrentMessage()
    }

    private enum class MessageType {
        LOGGING,
        WARNING,
        ERROR,
        OUTPUT
        ;

        companion object {
            fun getByTag(tag: String?): MessageType? = when (tag) {
                "logging" -> LOGGING
                "warning" -> WARNING
                "error" -> ERROR
                "output" -> OUTPUT
                else -> null
            }
        }
    }
}