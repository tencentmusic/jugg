@file:Suppress("IfThenToElvis", "CascadeIf")

package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import javax.tools.*
import javax.tools.JavaCompiler

/**
 * JavaCompilerInvoker invokes java operations and maps outputs/errors.
 */
class JavaCompilerInvoker {

    private var compiler: JavaCompiler = getJavaCompiler(Logger.getInstance(JavaCompilerInvoker::class.java))
    private var fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    private var hasRecreateAfterInternalError = false
    private var isSourceTargetVersionNotSupport: Boolean = false

    /**
     * Options carries isEnableApt, isAptOnly, isCanAutoRetry, and encoding.
     */
    data class Options(
        val isEnableApt: Boolean = false,
        val isAptOnly: Boolean = false, // if true, only run annotation processing, don't compile
        val isCanAutoRetry: Boolean = true,
        val encoding: String = "UTF-8",
        val aptPaths: List<File> = emptyList(),
        val aptOptions: Map<String, String> = emptyMap(),
        val aptSourcePaths: List<File> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

    fun compile(
        context: ICompileContext,
        module: ModuleInfo,
        task: CompileTask,
        logger: Logger,
        options: Options,
    ): CompileResult {
        logger.debug("compile options: $options")

        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val cmdOptions = mutableListOf("-d", task.outputDir.absolutePath)
        
        // dependencies
        val dependencies = options.dependencies.ifEmpty {
            context.getModuleDependencies(module, task)
        }
        cmdOptions.add("-g") // generate debug info, e.g. local variable name
        cmdOptions.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))

        // ensure class file version for later dex
        val sourceVersion = if (isSourceTargetVersionNotSupport) {
            "1.8" // at least 1.8
        } else if (module.javaSourceCompatibility != null) {
            module.javaSourceCompatibility
        } else {
            "1.8"
        }
        cmdOptions.addAll(listOf("-source", sourceVersion))
        
        val targetVersion = if (isSourceTargetVersionNotSupport) {
            "1.8" // at least 1.8
        } else if (module.javaSourceCompatibility != null) {
            module.javaTargetCompatibility
        } else {
            "1.8"
        }
        cmdOptions.addAll(listOf("-target", targetVersion))
        
        cmdOptions.addAll(listOf("-encoding", options.encoding))

        options.aptOptions.forEach { (key, value) ->
            cmdOptions.add("-A$key=$value")
        }

        if (options.isEnableApt) {
            val annotationProcessorPath = options.aptPaths
                .filter {
                    if (!it.exists()) {
                        logger.warn("Annotation processor dependency not found: $it, maybe sync again helps.")
                        return@filter false
                    }
                    true
                }
                .map { it.path }
                .toSet()

            if (annotationProcessorPath.isNotEmpty()) {
                cmdOptions.addAll(listOf("-processorpath", annotationProcessorPath.joinToString(File.pathSeparator)))
            }

            if (options.aptSourcePaths.isNotEmpty()) {
                cmdOptions.addAll(listOf("-sourcepath", options.aptSourcePaths.joinToString(File.pathSeparator)))
            }

            if (options.isAptOnly) {
                cmdOptions.add("-proc:only") // Only run annotation processing, don't compile
            }
        } else {
            cmdOptions.add("-proc:none") // The javac -proc option can be used to disable annotation processing
        }

        // APT processors may print normal progress as warning/error, keep these diagnostics at debug level.
        val forceCompilerOutputDebug = options.isEnableApt

        // compile error listener
        var isCurrentSourceTargetVersionNotSupport = false
        val notSourceErrors = mutableListOf<Pair<Long, String>>() // e.g. apt classpath error
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            val message = diagnostic.toString()
            if (diagnostic.kind != Diagnostic.Kind.ERROR || item == null) {
                if (item == null) {
                    notSourceErrors.add(diagnostic.lineNumber to message)
                }
                logger.debug("JavaCompiler output: [${diagnostic.kind}] $message")
                if (!isSourceTargetVersionNotSupport) {
                    val isVersionError = message.contains("不再支持") || message.contains("is no longer supported")
                    if (isVersionError) {
                        isCurrentSourceTargetVersionNotSupport = true
                    }
                }
                return@DiagnosticListener
            }
            if (forceCompilerOutputDebug) {
                logger.debug("JavaCompiler output: [${diagnostic.kind}] $message")
            } else {
                logger.warn(message)
            }
            item.errors.add(diagnostic.lineNumber to message)
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        logCompileCommand(module, cmdOptions, compileItems, logger)
        
        val javaTask = try {
            compiler.getTask(null, fileManager, compileListener, cmdOptions, null, objects)
        } catch (e: Exception) {
            logger.error("getTask failed", e)
            null
        }
        
        val isSuccess = javaTask?.call() ?: false

        // all failed or all success
        return if (isSuccess) {
            val outputs = task.outputDir.listFilesRecursively().map {
                if (it.extension == "class") {
                    CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
                } else if (it.extension == "java") {
                    CompileOutput(CompileOutput.Type.Java, it, task.outputDir)
                } else {
                    // e.g. META-INF/service/xxx
                    // Note: accessing context.moduleBelongsApkMap might be risky if context is abstracted, 
                    // but following KotlinInvoker pattern, we assume correct context.
                    val apkPath = context.moduleBelongsApkMap[module]?.apkFile?.path ?: ""
                    CompileOutput(CompileOutput.Type.Res, it, task.outputDir, apkPath)
                }
            }
            
            // Note: The original JavaCompiler copied outputs to javaClassPath here.
            // KotlinCompilerInvoker returns outputs and lets caller handle it, or handles it inside.
            // KotlinInvoker returns "CompileOutput" list. The caller (BaseCompiler?) usually moves them?
            // In KotlinCompilerInvoker: 
            //   it returns CompileResult with outputs.
            //   BUT it also did: "it.copyToBaseDir(kotlinClassPath, task.outputDir)" logic inside.
            // Checking JavaCompiler: it does "it.file.copyToBaseDir(task.outputDir, javaClassPath)" inside doModuleCompile.
            // To be consistent with "Invoker" doing the work:
            val javaClassPath = module.buildPathInfo.javaClassPath
            outputs.forEach {
                it.file.copyToBaseDir(task.outputDir, javaClassPath)
            }

            hasRecreateAfterInternalError = false
            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs)
        } else {
            // print infos
            logger.debug("Java compile failed, check classpath for : \n${module.buildPathInfo.buildDir}\n")
            context.printClasspathCheck(module)

            // retry strategy
            val errorCount = compileItems.sumOf { it.errors.size }
            var shouldRecreate = false
            var retryReason = ""
            
            if (isCurrentSourceTargetVersionNotSupport && !this.isSourceTargetVersionNotSupport) {
                retryReason = "Java compile failed with source or target version not support"
                this.isSourceTargetVersionNotSupport = true
                shouldRecreate = true
            } else if (errorCount > JuggSettings.minErrorToRecreateCompiler) {
                retryReason = "Java compile failed with too many errors(> ${JuggSettings.minErrorToRecreateCompiler})"
                shouldRecreate = true
            } else if (errorCount == 0) {
                if (notSourceErrors.isNotEmpty()) {
                    notSourceErrors.forEach { (_, message) ->
                        logger.warn(message)
                    }
                }
                logger.warn("Java compile failed with no error!")
                retryReason = "Java compile failed with no error"
                shouldRecreate = true
            }
            
            if (shouldRecreate) {
                logger.debug("try recreate compiler once, hasRecreateAfterInternalError: $hasRecreateAfterInternalError")
                if (options.isCanAutoRetry && !hasRecreateAfterInternalError) {
                    logger.warn("\n$retryReason, retry with recreating compiler once.\n")
                    hasRecreateAfterInternalError = true
                    compiler = getJavaCompiler(logger)
                    // Recursive call
                    return compile(context, module, task, logger, options)
                }
            }
            
            CompileResult(task, compileItems.map { Result.failure(CompileError(it.file, it.errors)) }, emptyList())
        }
    }

    private fun logCompileCommand(module: ModuleInfo, options: List<String>, files: List<JavaCompileItem>, logger: Logger) {
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

    /**
     * JavaCompileItem binds one source file, compiler file object, and collected diagnostics.
     */
    private class JavaCompileItem(
        val file: CompileFile,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    )

    companion object {

        var currentInstance = JavaCompilerInvoker()
            private set

        fun reset() {
            currentInstance = JavaCompilerInvoker()
        }

        fun getJavaCompiler(logger: Logger): JavaCompiler {
            var compiler: JavaCompiler? = ToolProvider.getSystemJavaCompiler()
            if (compiler != null) {
                return compiler
            }
            logger.debug("get JavaCompiler failed by ToolProvider.getSystemJavaCompiler(), try use reflect")
            try {
                @Suppress("DEPRECATION")
                compiler = Class.forName("com.sun.tools.javac.api.JavacTool").newInstance() as JavaCompiler
                logger.debug("get JavaCompiler by JavacTool success")
                logger.debug("JavaCompiler name: ${compiler.name()}, sourceVersions: ${compiler.sourceVersions}")
                return compiler
            } catch (e: Exception) {
                logger.warn("get JavaCompiler by JavacTool failed", e)
                throw JuggException.getJavaCompilerFailed()
            }
        }
    }
}
