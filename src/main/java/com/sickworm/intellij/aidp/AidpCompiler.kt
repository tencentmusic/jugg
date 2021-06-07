package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler as JavaCompilerX


private val logger = Logger.getInstance("#AIDP-Compiler")

interface ICompiler {
    fun compile(task: CompileTask): CompileResult
}

data class CompileTask(
    val files: List<CompileFileInfo>,
    val outputDir: File
) {

    operator fun plus(task: CompileTask): CompileTask {
        return CompileTask(files + task.files, outputDir)
    }

    companion object
}

fun CompileTask.Companion.singleFile(filePath: String, outputDir: String, dependencies: List<String> = emptyList()) =
    CompileTask(listOf(CompileFileInfo(File(filePath), dependencyPaths = dependencies)), File(outputDir))

data class CompileFileInfo(
    val file: File,
    val type: Type = getTypeByExtension(file.name),
    val dependencyPaths: List<String> = emptyList()
) {

    override fun toString(): String {
        return "$type:${file.name}"
    }

    companion object {
        fun getTypeByExtension(fileName: String): Type {
            return when {
                fileName.endsWith(".java") -> Type.JAVA
                else -> Type.OTHER
            }
        }
    }

    enum class Type {
        JAVA,
        OTHER
    }
}

data class CompileResult(
    val task: CompileTask,
    val details: List<Result<CompileFileInfo, CompileError>>
): List<Result<CompileFileInfo, CompileError>> by details {

    val fileCount get() = details.size

    val successFiles get() = details.filter { it.isSuccess }

    val failedFiles get() = details.filter { it.isFailed }
}

data class CompileError(
    /** file to be compiled */
    val file: CompileFileInfo,
    /** will be empty if [file] looks good but compiler still stopped because there is another error file */
    val errors: List<Pair<Long, String>> // <Line, Message>
) {
    val errorMessages get() = errors.joinToString("\n") { it.second }
}

class AidpCompiler: ICompiler {

    private val javaCompiler = JavaCompiler()

    override fun compile(task: CompileTask): CompileResult {
        // split compile files by type
        val fileSet = mutableMapOf<CompileFileInfo.Type, MutableList<CompileFileInfo>>()
        task.files.forEach {
            var set = fileSet[it.type]
            if (set == null) {
                set = mutableListOf()
                fileSet[it.type] = set
            }
            set.add(it)
        }

        // compile
        val startTime = System.currentTimeMillis()
        val resultList: List<CompileResult?> = fileSet.map { (type, files) ->
            when (type) {
                CompileFileInfo.Type.JAVA -> {
                    logger.info("compile java files $files")
                    javaCompiler.compile(task)
                }
                CompileFileInfo.Type.OTHER -> {
                    logger.info("ignore files $files")
                    null
                }
            }
        }
        val costTime = System.currentTimeMillis() - startTime
        logger.info("compile finished, cost ${costTime}ms")

        // check result
        val details = resultList
            .mapNotNull { it?.details }
            .flatten()
        val result = CompileResult(task, details)
        logger.info("compile result, success: ${result.successFiles.size}, failure: ${result.failedFiles.size}")

        // log error
        result.failedFiles.forEach {
            val fileName = it.file.file.name
            val error = it.getFailure()
            logger.warn("$fileName compile failed! errors(total ${error.errors.size}):")
            it.getFailureOrNull()?.errorMessages?.split("\n")?.forEach { msg ->
                logger.warn(msg)
            }
            logger.warn("$fileName compile failed! please check out the log")
        }

        return result
    }
}

class JavaCompiler: ICompiler {

    private val compiler: JavaCompilerX = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun compile(task: CompileTask): CompileResult {
        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        options.addAll(listOf("-cp", dependencies.joinToString(pathSeparator)))

        // compile error listener
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.first { it.fileObject == diagnostic.source }
            item.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        javaTask.call()

        // check result
        val failedItems = compileItems.filter { it.isFailed }
        // all failed or all success
        return if (failedItems.isEmpty()) {
            CompileResult(task, compileItems.map { Result.success(it.file) })
        } else {
            CompileResult(task, compileItems.map { Result.failure(CompileError(it.file, it.errors)) })
        }
    }

    private class JavaCompileItem(
        val file: CompileFileInfo,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    ) {
        val isFailed get() = errors.isNotEmpty()
    }
}

class KotlinCompiler: ICompiler {
    override fun compile(task: CompileTask): CompileResult {
        val javaCmd = "D:\\Java\\jdk1.8.0_77\\bin\\java.exe"
        val preloader = "D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-preloader.jar org.jetbrains.kotlin.preloading.Preloader"
        val compiler = "D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        val command = "$javaCmd -Xmx256M -Xms32M -noverify -cp $preloader -cp $compiler ${task.files[0].file.absolutePath} -d ${task.outputDir}"
        println(command)
        val pr = Runtime.getRuntime().exec(command)
        pr.waitFor()
        return CompileResult(task, listOf(Result.success(task.files[0])))
    }
}

val pathSeparator = System.getProperty("path.separator")

val Result<CompileFileInfo, CompileError>.file: CompileFileInfo
    get() = if (isSuccess) getOrNull()!! else getFailureOrNull()!!.file