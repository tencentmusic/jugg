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

    private var isSourceTargetVersionNotSupport: Boolean = false

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeSource
    override val afterCompileOrderRange: IntRange = CompileOrder.afterSource

    @Suppress("IfThenToElvis")
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
        val sourceVersion = if (isSourceTargetVersionNotSupport) {
            "1.8" // at least 1.8
        } else if (module.javaSourceCompatibility != null) {
            module.javaSourceCompatibility
        } else {
            "1.8"
        }
        options.addAll(listOf("-source", sourceVersion))
        val targetVersion = if (isSourceTargetVersionNotSupport) {
            "1.8" // at least 1.8
        } else if (module.javaTargetCompatibility != null) {
            module.javaTargetCompatibility
        } else {
            "1.8"
        }
        options.addAll(listOf("-target", targetVersion))
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
        var isSourceTargetVersionNotSupport = false
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            val message = diagnostic.toString()
            if (diagnostic.kind != Diagnostic.Kind.ERROR || item == null) {
                logger.debug("JavaCompiler output: [${diagnostic.kind}] $message")
                if (!isSourceTargetVersionNotSupport) {
                    isSourceTargetVersionNotSupport = message.contains("不再支持") || message.contains("is no longer supported")
                }
                return@DiagnosticListener
            }
            logger.warn(message)
            item.errors.add(diagnostic.lineNumber to message)
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
                    CompileOutput(CompileOutput.Type.Res, it, task.outputDir, context.moduleBelongsApkMap[module]!!.apkFile.path)
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
            logger.debug("Java compile failed, check classpath for : \n${module.buildPathInfo.buildDir}\n")
            context.printClasspathCheck(module)

            // retry strategy
            val errorCount = compileItems.sumOf { it.errors.size }
            var shouldRecreate = false
            var retryReason = ""
            if (isSourceTargetVersionNotSupport && !this.isSourceTargetVersionNotSupport) {
                retryReason = "Java compile failed with source or target version not support"
                this.isSourceTargetVersionNotSupport = true
                shouldRecreate = true
            } else if (errorCount > JuggSettings.minErrorToRecreateCompiler) {
                // most likely kotlin compiler is not working, try to recreate once
                retryReason = "Java compile failed with too many errors(> ${JuggSettings.minErrorToRecreateCompiler})"
                shouldRecreate = true
            } else if (errorCount == 0) {
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
                logger.debug("get JavaCompiler by JavacTool success")
                // sourceVersions is useless, it cannot to detect what source versions are supported by compiler
                logger.debug("JavaCompiler name: ${compiler.name()}, sourceVersions: ${compiler.sourceVersions}")
                return compiler
            } catch (e: Exception) {
                logger.warn("get JavaCompiler by JavacTool failed", e)
                throw JuggException.getJavaCompilerFailed()
            }
        }
    }
}