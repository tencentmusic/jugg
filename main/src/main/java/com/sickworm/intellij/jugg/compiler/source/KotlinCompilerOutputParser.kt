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
        if (b == '\n'.code) {
            onNewLine(line.toString())
            line.clear()
        } else {
            line.append(b.toChar())
        }
    }

    val outputs: List<File>
        get() = innerOutputs.flatMap { it.value }
    val results: List<Result<CompileFile, CompileError>>
        get() = files.map { file ->
            val errorDetails = innerErrors[file]
            if (errorDetails != null) {
                Result.failure(CompileError(file, errorDetails))
            } else if (innerOutputs.keys.any { it.absolutePath == file.file.absolutePath}){
                Result.success(file)
            } else {
                Result.failure(CompileError(file, listOf(-1L to "no outputs")))
            }
        }

    private val innerErrors = mutableMapOf<CompileFile, MutableList<Pair<Long, String>>>()
    /** Map<SourceFile, List<OutputClassFile>> */
    private val innerOutputs = mutableMapOf<File, MutableList<File>>()

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
                val parsedMessage = parseErrorMessage(message)
                logger.warn(parsedMessage)
            }
            MessageType.OUTPUT -> {
                logger.debug(message)
                parseOutputMessage(message)
            }
        }
        currentMessage.clear()
    }

    private val errorRegex = Regex("(.*):(.*):(.*): error: (.*)")

    private fun parseErrorMessage(message: String): String {
        // e.g.
        // src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/sickworm/jugg/demo/ #soft wrap
        // testcase/CaseKtSmartCast.kt:9:26: error: smart cast to 'MutableList<String>' is impossible, #soft wrap
        // because 'class2.dataList' is a public API property declared in different module
        // e.g.2.
        // error: the Android extensions ('kotlin-android-extensions') compiler plugin is no longer supported.  #soft wrap
        // Please use kotlin parcelize and view binding. #soft wrap
        // More information: https://goo.gle/kotlin-android-extensions-deprecation
        val contents = errorRegex.find(message)
        val filePath = contents?.groups?.get(1)?.value?: ""
        if (filePath.isEmpty()) {
            // a common error, not a specific file error, like e.g.2.
            // add it to all files
            files.forEach {
                innerErrors.getOrPut(it) { mutableListOf() }.add(-1L to message)
            }
            return message
        }

        val line = contents?.groups?.get(2)?.value?.toLongOrNull()?: -1L
        val file = files.find { it.file.absolutePath.endsWith(filePath) }
        if (file == null) {
            logger.debug("failed to parse error message: $message")
            return message
        }

        if (!innerErrors.containsKey(file)) {
            innerErrors[file] = mutableListOf()
        }
        innerErrors[file]?.add(line to message)

        // replace to absolute path to make it clickable in IDE
        return message.replace(filePath, file.file.absolutePath)
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
            logger.debug("Failed to parse output message with no line wrap: $message")
            return
        }

        val outputFiles = mutableListOf<File>()
        val sourceFile = mutableListOf<File>()
        // first line is "output: output:", ignore
        for (i in 1 until contents.size) {
            val filePath = contents[i]
            if (filePath == "Sources:") {
                for (j in i + 1 until contents.size) {
                    val file = File(contents[j])
                    if (file.isFile) {
                        sourceFile.add(File(contents[j]))
                    }
                }
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

        if (sourceFile.isEmpty()) {
            logger.warn("Failed to parse output message with no source file: $message")
            return
        }

        if (outputFiles.isEmpty()) {
            logger.warn("Failed to parse output message with no output file: $message")
            return
        }

        sourceFile.forEach {
            if (!innerOutputs.containsKey(it)) {
                innerOutputs[it] = mutableListOf()
            }
            innerOutputs[it]?.addAll(outputFiles)
        }
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