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
)

data class CompileFileInfo(
    val file: File,
    val type: Type = getTypeByExtension(file.name),
    val dependencyPaths: List<String> = emptyList()
) {

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
)

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

        val result: List<List<Result<CompileFileInfo, CompileError>>?> = fileSet.map { (type, files) ->
            when (type) {
                CompileFileInfo.Type.JAVA -> {
                    logger.info("compile java files ${files.toTypedArray().contentToString()}")
                    javaCompiler.compile(task)
                }
                CompileFileInfo.Type.OTHER -> {
                    logger.info("ignore files ${files.toTypedArray().contentToString()}")
                    null
                }
            }
        }

        return result.filterNotNull().flatten()
    }
}

class JavaCompiler: ICompiler {

    private val classPathSeparate = if (System.getProperty("os.name").startsWith("Windows")) ";" else ":"

    override fun compile(task: CompileTask): List<Result<CompileFileInfo, CompileError>> {
        val compiler: JavaCompilerX = ToolProvider.getSystemJavaCompiler()
        val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

        val compileItems = task.files.associate {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            fileObject to JavaCompileItem(it, fileObject)
        }
        val objects = compileItems.values.map { it.fileObject}

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