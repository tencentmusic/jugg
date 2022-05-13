package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.*
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler

class JavaCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Java)

    override val isNeedOutputDirEmpty = true

    private val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun doCompile(task: CompileTask): CompileResult {// split by module
        val files = task.files.groupBy { it.module.name }
        val results = files.map {
            doModuleCompile(CompileTask(it.value, task.outputDir), it.value[0].module)
        }
        if (results.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }
        return results.reduce { acc, compileResult -> acc + compileResult }
    }

    private fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        options.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        // ensure class file version for later dex
        options.addAll(listOf("-source", module.javaSourceCompatibility ?: "1.6"))
        options.addAll(listOf("-target", module.javaTargetCompatibility ?: "1.6"))

        // compile error listener
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            if (diagnostic.kind != Diagnostic.Kind.ERROR || item == null) {
                logger.debug(diagnostic.toString())
                return@DiagnosticListener
            }
            logger.error(diagnostic.toString())
            item.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        if (logger.isTraceEnabled) {
            logger.trace("Compile files: $objects")
            logger.trace("Compile options: $options")
        }
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        if (!javaTask.call()) {
            logger.error("javaTask call failed!")
        }

        // check result
        val failedItems = compileItems.filter { it.isFailed }
        // all failed or all success
        return if (failedItems.isEmpty()) {
            val outputs = task.outputDir.listFilesRecursively().map {
                CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
            }

            // copy outputs to java class path
            val javaClassPath = module.buildPathInfo.javaClassPath
            outputs.forEach {
                it.file.copyToBaseDir(task.outputDir, javaClassPath)
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