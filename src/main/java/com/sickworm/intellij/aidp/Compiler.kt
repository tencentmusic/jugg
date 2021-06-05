package com.sickworm.intellij.aidp

import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler as JavaCompilerX


interface ICompiler {
    fun compile(info: CompileFileInfo): Result<Unit, CompileError>
}

data class CompileFileInfo(
    val file: File,
    val destDir: File
)

data class CompileError(
    val errors: List<Pair<Long, String>> // <Line, Message>
)

class JavaCompiler: ICompiler {
    override fun compile(info: CompileFileInfo): Result<Unit, CompileError> {
        val compiler: JavaCompilerX = ToolProvider.getSystemJavaCompiler()
        val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

        val errors = mutableListOf<Diagnostic<out JavaFileObject>>()
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            errors.add(diagnostic)
        }

        val compileFiles = fileManager.getJavaFileObjectsFromFiles(listOf(info.file))
        val options: List<String> = listOf("-d", info.destDir.absolutePath)
        val task = compiler.getTask(null, fileManager, compileListener, options, null, compileFiles)
        val result = task.call()

        return if (result) {
            Result.success(Unit)
        } else {
            Result.failure(CompileError(errors.map { it.lineNumber to it.toString() }))
        }
    }
}