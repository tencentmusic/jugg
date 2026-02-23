package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileError
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.Result
import kotlinx.metadata.jvm.JvmMetadataVersion
import java.io.File
import java.io.OutputStream
import java.io.PrintStream

/**
 * KotlinCompilerOutputParser parses kotlin output into structured models.
 */
class KotlinCompilerOutputParser(
    private val files: List<CompileFile>,
    private val logger: Logger,
    private val forceCompilerOutputDebug: Boolean = false,
) {

    private val outputStream = object : OutputStream() {
        override fun write(b: Int) {
            onInput(b)
        }
    }
    val printStream = PrintStream(outputStream)

    private val myBuffer = ByteArray(100000) // 100KB
    private var myPosition = 0
    private fun onInput(b: Int) {
        if (b == '\n'.code) {
            val line = String(myBuffer, 0, myPosition)
            onNewLine(line)
            myPosition = 0
        } else {
            if (myPosition == myBuffer.size) {
                return
            }
            myBuffer[myPosition++] = b.toByte()
        }
    }

    val outputs: List<File>
        get() = innerOutputs.flatMap { it.value }

    fun getResult(isCompileSuccess: Boolean): List<Result<CompileFile, CompileError>> {
        return files.map { file ->
            val errorDetails = innerErrors[file]
            if (errorDetails != null) {
                Result.failure(CompileError(file, errorDetails))
            } else if (innerOutputs.keys.any { it.absolutePath == file.file.absolutePath}) {
                Result.success(file)
            } else {
                logger.debug("File ${file.file.absolutePath} has no output, mark as success")
                // compat for parse output, it's ok to just read result code of KotlinCompiler
                if (isCompileSuccess) {
                    Result.success(file)
                } else {
                    Result.failure(CompileError(file, listOf(-1L to "compile failed")))
                }
            }
        }
    }

    var metadataVersionErrors = mutableListOf<MetadataVersionError>()
        private set

    var isGotParcelizeClassCastException: Boolean = false
        private set

    private val innerErrors = mutableMapOf<CompileFile, MutableList<Pair<Long, String>>>()
    /** Map<SourceFile, List<OutputClassFile>> */
    private val innerOutputs = mutableMapOf<File, MutableList<File>>()

    val currentMessage = StringBuilder()
    private var currentMessageType: MessageType = MessageType.LOGGING

    private fun onNewLine(line: String) {
        val contents = MessageType.newLineRegex.find(line)?.groups
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
                if (forceCompilerOutputDebug) {
                    logger.debug(parsedMessage)
                } else {
                    logger.warn(parsedMessage)
                }
            }
            MessageType.OUTPUT -> {
                logger.debug(message)
                parseOutputMessage(message)
            }
            MessageType.EXCEPTION -> {
                logger.debug(message)
                parseExceptionMessage(message)
            }
        }
        currentMessage.clear()
    }

    private val errorRegex = Regex("(.*):(.*):(.*): error: (.*)")

    private fun parseErrorMessage(message: String): String {

        // e.g.
        // /Users/sickworm/MyApplication/build/jugg/classpath/root/MyApplication/app/build/tmp/kotlin-classes/
        // debug/META-INF/app_debug.kotlin_module: error: module was compiled with an incompatible version of Kotlin.
        // The binary version of its metadata is 1.7.0, expected version is 1.1.16.
        if (MetadataVersionError.isMyError(message)) {
            val error = MetadataVersionError.create(message)
            if (error != null) {
                metadataVersionErrors.add(error)
            }
            logger.debug("found metadata error message: $message, error: $error")
            return message
        }


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

    private fun parseExceptionMessage(message: String) {
        // handles exception: java.lang.ClassCastException: Cannot cast org.jetbrains.kotlin.parcelize.ParcelizeComponentRegistrar
        // to org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
        if (message.contains("java.lang.ClassCastException") && message.contains("parcelize")) {
            isGotParcelizeClassCastException = true
        }
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
            val filePath = contents[i].trim().replace("\n", "")
            if (filePath == "Sources:") {
                for (j in i + 1 until contents.size) {
                    val sourceFilePath = contents[j].trim().replace("\n", "")
                    val file = File(sourceFilePath)
                    if (file.exists()) {
                        sourceFile.add(file)
                    } else {
                        logger.debug("Failed to parse output message, source file not exists: $file")
                    }
                }
                // reaches end
                break
            }

            val file = File(filePath)
            if (file.exists()) {
                outputFiles.add(file)
            } else {
                logger.debug("Failed to parse output message, output file not exists: $file")
            }
        }

        if (sourceFile.isEmpty()) {
            if (message.contains(".kotlin_module")) {
                logger.debug("(.kotlin_module) Failed to parse output message with no source file: $message")
            } else {
                if (forceCompilerOutputDebug) {
                    logger.debug("Failed to parse output message with no source file: $message")
                } else {
                    logger.warn("Failed to parse output message with no source file: $message")
                }
            }
            // compat for parse output, it's ok to just read result code of KotlinCompiler
            sourceFile.add(File("unknown"))
        }

        if (outputFiles.isEmpty()) {
            if (forceCompilerOutputDebug) {
                logger.debug("Failed to parse output message with no output file: $message")
            } else {
                logger.warn("Failed to parse output message with no output file: $message")
            }
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

    /**
     * MessageType classifies parsed compiler-output frames for logging/error/output routing.
     */
    private enum class MessageType {
        LOGGING,
        WARNING,
        ERROR,
        OUTPUT,
        EXCEPTION,
        ;

        companion object {

            val newLineRegex = Regex("(.*):?(logging|warning|error|output|exception):(.*)")

            fun getByTag(tag: String?): MessageType? = when (tag) {
                "logging" -> LOGGING
                "warning" -> WARNING
                "error" -> ERROR
                "output" -> OUTPUT
                "exception" -> EXCEPTION
                else -> null
            }
        }
    }

    /**
     * MetadataVersionError carries message, metadataFile, actualVersion, and expectVersion.
     */
    data class MetadataVersionError(
        val message: String,
        val metadataFile: File,
        val actualVersion: String,
        val expectVersion: String,
        val expectMetadataVersion: JvmMetadataVersion,
    ) {

        companion object {

            fun isMyError(message: String): Boolean {
                return message.contains("metadata") && message.contains("expected version")
            }

            fun create(message: String): MetadataVersionError? {
                if (!isMyError(message)) {
                    return null
                }

                // The binary version of its metadata is 1.7.0, expected version is 1.1.16.
                val regex = Regex("(.*): error: .* is ([0-9.]+), expected version is ([0-9.]+)\\.")
                val matchResult = regex.find(message)
                if (matchResult == null || matchResult.groups.size != 4) {
                    return null
                }

                val metadataFile = File(matchResult.groups[1]!!.value)
                val actualVersion = matchResult.groups[2]!!.value
                val expectVersion = matchResult.groups[3]!!.value
                val expectMetadataVersion: JvmMetadataVersion? = run {
                    val splits = expectVersion.split(".")
                    val major = splits.getOrNull(0)?.toIntOrNull()
                    val minor = splits.getOrNull(1)?.toIntOrNull()
                    val patch = splits.getOrNull(2)?.toIntOrNull()
                    if (major == null || minor == null) {
                        return@run null
                    }
                    return@run JvmMetadataVersion(major, minor, patch ?: 0)
                }

                if (expectMetadataVersion == null) {
                    return null
                }

                return MetadataVersionError(message, metadataFile, actualVersion, expectVersion, expectMetadataVersion)
            }
        }
    }
}
