package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.*
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler

class JavaCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Java)

    override val isNeedOutputDirEmpty = true

    private val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun doCompile(task: CompileTask): CompileResult {
        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        options.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        // ensure class file version for later dex
        options.addAll(listOf("-source", "1.8"))
        options.addAll(listOf("-target", "1.8"))

        // compile error listener
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            logger.warn(diagnostic.toString())
            if (diagnostic.kind != Diagnostic.Kind.ERROR || item == null) {
                return@DiagnosticListener
            }
            item.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        if (!javaTask.call()) {
            logger.warn("javaTask call failed!")
        }

        // check result
        val failedItems = compileItems.filter { it.isFailed }
        // all failed or all success
        return if (failedItems.isEmpty()) {
            val outputs = task.outputDir.listFilesRecursively().map {
                CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
            }
            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs)
        } else {
            CompileResult(task, compileItems.map { Result.failure(CompileError(it.file, it.errors)) }, emptyList())
        }
    }

    private class JavaCompileItem(
        val file: CompileFile,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    ) {
        val isFailed get() = errors.isNotEmpty()
    }
}