package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler as JavaCompilerX


private val logger = Logger.getInstance("#AIDP-Compiler")

interface ICompiler {
    fun compile(task: CompileTask): List<Result<CompileFileInfo, CompileError>>
}

data class CompileTask(
    val files: List<CompileFileInfo>,
    val outputDir: File
) {
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

data class CompileError(
    val file: CompileFileInfo,
    val errors: List<Pair<Long, String>> // <Line, Message>
) {
    val errorMessages get() = errors.joinToString("\n") { it.second }
}

class AidpCompiler: ICompiler {

    private val javaCompiler = JavaCompiler()

    override fun compile(task: CompileTask): List<Result<CompileFileInfo, CompileError>> {
        val fileSet = mutableMapOf<CompileFileInfo.Type, MutableList<CompileFileInfo>>()
        task.files.forEach {
            var set = fileSet[it.type]
            if (set == null) {
                set = mutableListOf()
                fileSet[it.type] = set
            }
            set.add(it)
        }

        val startTime = System.currentTimeMillis()
        val resultList: List<List<Result<CompileFileInfo, CompileError>>?> = fileSet.map { (type, files) ->
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

        val result = resultList.filterNotNull().flatten().toList()

        val successResult = result.filter { it.isSuccess }
        val failureResult = result.filter { it.isFailure }
        logger.info("compile result, success: ${successResult.size}, failure: ${failureResult.size}")
        failureResult.forEach {
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

    private val classPathSeparate = System.getProperty("path.separator")
    private val compiler: JavaCompilerX = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun compile(task: CompileTask): List<Result<CompileFileInfo, CompileError>> {
        val compileItems = task.files.associate {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            fileObject to JavaCompileItem(it, fileObject)
        }
        val objects = compileItems.values.map { it.fileObject }

        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        if (dependencies.isNotEmpty()) {
            options.addAll(listOf("-cp", dependencies.joinToString(classPathSeparate)))
        }

        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            compileItems[diagnostic.source]!!.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        javaTask.call()

        return compileItems.values.map {
            if (it.isSuccess) Result.success(it.file) else Result.failure(it.toCompileError())
        }
    }

    private class JavaCompileItem(
        val file: CompileFileInfo,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    ) {
        val isSuccess get() = errors.isEmpty()

        fun toCompileError() = CompileError(file, errors)
    }
}

val Result<CompileFileInfo, CompileError>.file: CompileFileInfo
    get() = if (isSuccess) getOrNull()!! else getFailureOrNull()!!.file