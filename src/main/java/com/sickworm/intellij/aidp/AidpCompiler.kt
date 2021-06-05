package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler as JavaCompilerX


private val logger = Logger.getInstance("#AIDP-Compiler")

interface ICompiler {
    fun compile(files: List<CompileFileInfo>, outputDir: File): List<Result<Unit, CompileError>>
}

data class CompileFileInfo(
    val file: File,
    val type: Type = getTypeByExtension(file.name)
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

    override fun compile(files: List<CompileFileInfo>, outputDir: File): List<Result<Unit, CompileError>> {
        val fileSet = mutableMapOf<CompileFileInfo.Type, MutableList<CompileFileInfo>>()
        files.forEach {
            var set = fileSet[it.type]
            if (set == null) {
                set = mutableListOf()
                fileSet[it.type] = set
            }
            set.add(it)
        }

        val result: List<List<Result<Unit, CompileError>>?> = fileSet.map { (type, files) ->
            when (type) {
                CompileFileInfo.Type.JAVA -> javaCompiler.compile(files, outputDir)
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
    override fun compile(files: List<CompileFileInfo>, outputDir: File): List<Result<Unit, CompileError>> {
        val compiler: JavaCompilerX = ToolProvider.getSystemJavaCompiler()
        val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)
        val options: List<String> = listOf("-d", outputDir.absolutePath)
        val compileItems = files.associate {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            fileObject to JavaCompileItem(it, fileObject)
        }
        val objects = compileItems.values.map { it.fileObject}

        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            compileItems[diagnostic.source]!!.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }
        val task = compiler.getTask(null, fileManager, compileListener, options, null, objects )
        task.call()

        return compileItems.values.map {
            if (it.isSuccess) Result.success(Unit) else Result.failure(it.toCompileError())
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