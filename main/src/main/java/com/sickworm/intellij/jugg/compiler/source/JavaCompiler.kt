package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler

class JavaCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Java)

    override val isNeedOutputDirEmpty = true

    override val isNeedPrintProgress: Boolean = true

    private val compiler: JavaCompiler = getJavaCompiler(context.logger)
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = context.getModuleDependencies(module, task)
        options.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        // ensure class file version for later dex
        options.addAll(listOf("-source", module.javaSourceCompatibility ?: "1.8"))
        options.addAll(listOf("-target", module.javaTargetCompatibility ?: "1.8"))

        // compile error listener
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            if (diagnostic.kind != Diagnostic.Kind.ERROR || item == null) {
                logger.debug(diagnostic.toString())
                return@DiagnosticListener
            }
            logger.warn(diagnostic.toString())
            item.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        logCompileCommand(module, options, compileItems)
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        if (!javaTask.call()) {
            logger.debug("javaTask call failed!")
        }

        // check result
        val failedItems = compileItems.filter { it.isFailed }
        // all failed or all success
        return if (failedItems.isEmpty()) {
            val outputs = task.outputDir.listFilesRecursively().map {
                CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
            }

            if (module != ModuleInfo.virtualModule) {
                // copy outputs to java class path
                val javaClassPath = module.buildPathInfo.javaClassPath
                outputs.forEach {
                    it.file.copyToBaseDir(task.outputDir, javaClassPath)
                }
            }

            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs)
        } else {
            CompileResult(task, compileItems.map { Result.failure(CompileError(it.file, it.errors)) }, emptyList())
        }
    }

    private fun logCompileCommand(module: ModuleInfo, options: List<String>, files: List<JavaCompileItem>) {
        val baseDir = module.buildPathInfo.buildDir

        var lastOption = ""
        val shortOptions = options.map {
            if (lastOption == "-cp") {
                return@map it
                    .split(File.pathSeparator)
                    .joinToString(File.pathSeparator) { cpPath ->
                        File(cpPath).relativeToOrSelf(baseDir).path
                    }
            }
            lastOption = it

            if (!it.startsWith('/')) {
                return@map it
            }
            val file = File(it).relativeToOrSelf(baseDir)
            return@map file.path
        }
        val shortFiles = files.map {
            it.file.file.relativeToOrSelf(baseDir)
        }
        logger.debug("java compile base dir: $baseDir")
        logger.debug("java compile: javac " +
                shortOptions.joinToString(" ") +
                " " +
                shortFiles.joinToString(" ")
        )
    }

    private class JavaCompileItem(
        val file: CompileFile,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    ) {
        val isFailed get() = errors.isNotEmpty()
    }

    companion object {

        fun getJavaCompiler(logger: Logger): JavaCompiler {
            var compiler: JavaCompiler? = ToolProvider.getSystemJavaCompiler()
            if (compiler == null) {
                logger.debug("get JavaCompiler failed by ToolProvider.getSystemJavaCompiler(), try use reflect")
            }
            try {
                @Suppress("DEPRECATION")
                compiler = Class.forName("com.sun.tools.javac.api.JavacTool").newInstance() as JavaCompiler
                logger.info("get JavaCompiler by JavacTool success")
                return compiler
            } catch (e: Exception) {
                logger.warn("get JavaCompiler by JavacTool failed", e)
                throw JuggException.getJavaCompilerFailed()
            }
        }
    }
}