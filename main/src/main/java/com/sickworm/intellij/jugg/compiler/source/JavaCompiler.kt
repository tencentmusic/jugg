package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler

class JavaCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Java)

    override val isNeedOutputDirEmpty = true

    override val isNeedPrintProgress: Boolean = true

    private var hasRecreateAfterInternalError = false

    private var compiler: JavaCompiler = getJavaCompiler(logger)
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    private val isEnableApt get() = JuggSettings.isEnableApt

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = context.getModuleDependencies(module, task)
        options.add("-g") // generate debug info, e.g. local variable name
        options.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        // ensure class file version for later dex
        options.addAll(listOf("-source", module.javaSourceCompatibility ?: "1.8"))
        options.addAll(listOf("-target", module.javaTargetCompatibility ?: "1.8"))
        options.addAll(listOf("-encoding", "UTF-8"))
        module.javaAnnotationProcessorOptions?.forEach { (key, value) ->
            options.addAll(listOf("-A$key=\"$value\""))
        }

        if (isEnableApt) {
            val annotationProcessorPath = (module.annotationProcessorDependencies + module.kaptDependencies)
                .filter {
                    if (!it.file.exists()) {
                        logger.warn("Annotation processor dependency not found: $it, maybe sync again helps.")
                        return@filter false
                    }
                    true
                }
                .map { it.file.path }
                .toSet()
            options.addAll(listOf("-processorpath", annotationProcessorPath.joinToString(File.pathSeparator)))
            options.addAll(listOf("-sourcepath", module.sourceDirs.joinToString(File.pathSeparator)))
        } else {
            options.add("-proc:none") // The javac -proc option can be used to disable annotation processing
            // see https://openjdk.org/groups/compiler/processing-code.html
        }

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
        val isSuccess = javaTask.call() // true if and only all the files compiled without errors; false otherwise

        // all failed or all success
        return if (isSuccess) {
            val outputs = task.outputDir.listFilesRecursively().map {
                if (it.extension == "class") {
                    CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
                } else if (it.extension == "java") {
                    CompileOutput(CompileOutput.Type.Java, it, task.outputDir)
                } else {
                    // e.g. META-INF/service/xxx
                    CompileOutput(CompileOutput.Type.Res, it, task.outputDir)
                }
            }

            // copy outputs to java class path
            val javaClassPath = module.buildPathInfo.javaClassPath
            outputs.forEach {
                it.file.copyToBaseDir(task.outputDir, javaClassPath)
            }

            hasRecreateAfterInternalError = false
            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs)
        } else {
            // print infos
            context.printClasspathCheck(module)

            // retry strategy
            val errorCount = compileItems.sumOf { it.errors.size }
            var shouldRecreate = false
            var retryReason = ""
            if (errorCount > JuggSettings.minErrorToRecreateCompiler) {
                // most likely kotlin compiler is not working, try to recreate once
                retryReason = "Java compile failed with too many errors(> ${JuggSettings.minErrorToRecreateCompiler})"
                shouldRecreate = true
            }
            if (errorCount == 0) {
                logger.warn("Java compile failed with no error!")
                retryReason = "Java compile failed with no error"
                shouldRecreate = true
            }
            if (shouldRecreate) {
                logger.debug("try recreate compiler once, hasRecreateAfterInternalError: $hasRecreateAfterInternalError")
                if (!hasRecreateAfterInternalError) {
                    logger.warn("\n$retryReason, retry with recreating compiler once.\n")
                    hasRecreateAfterInternalError = true
                    compiler = getJavaCompiler(logger)
                    return doModuleCompile(task, module)
                }
            }
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
            if (compiler != null) {
                return compiler
            }
            logger.debug("get JavaCompiler failed by ToolProvider.getSystemJavaCompiler(), try use reflect")
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