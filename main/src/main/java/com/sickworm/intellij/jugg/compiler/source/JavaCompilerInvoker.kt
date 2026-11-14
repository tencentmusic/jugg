@file:Suppress("IfThenToElvis", "CascadeIf")

package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.BuildToolsVersionComparator
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.spi.FileSystemProvider
import javax.tools.*
import javax.tools.JavaCompiler

/**
 * JavaCompilerInvoker invokes java operations and maps outputs/errors.
 */
class JavaCompilerInvoker {

    private var compiler: JavaCompiler = getJavaCompiler(Logger.getInstance(JavaCompilerInvoker::class.java))

    private var hasRecreateAfterInternalError = false
    private var isSourceTargetVersionNotSupport: Boolean = false

    /**
     * Options carries language and annotation-processing configuration for one invocation.
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
        val processorNames: List<String> = emptyList(),
        val generatedSourceDir: File? = null,
    )

    fun compile(
        context: ICompileContext,
        module: ModuleInfo,
        task: CompileTask,
        logger: Logger,
        options: Options,
    ): CompileResult {
        logger.debug("compile options: $options")

        // A fresh manager releases classpath JAR handles after this run, allowing Gradle clean on Windows.
        var fileManager = compiler.getStandardFileManager(null, null, null)
        var compileItems = task.files.map {
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

        if (options.aptSourcePaths.isNotEmpty()) {
            cmdOptions.addAll(listOf("-sourcepath", options.aptSourcePaths.joinToString(File.pathSeparator)))
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

            if (options.isAptOnly) {
                cmdOptions.add("-proc:only") // Only run annotation processing, don't compile
            }
        } else {
            cmdOptions.add("-proc:none") // The javac -proc option can be used to disable annotation processing
        }

        if (options.isEnableApt && options.processorNames.isNotEmpty()) {
            cmdOptions.addAll(listOf("-processor", options.processorNames.joinToString(",")))
        }
        options.generatedSourceDir?.let {
            it.mkdirs()
            cmdOptions.addAll(listOf("-s", it.absolutePath))
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
            // Prefix error with locale-independent diagnostic code for reliable keyword matching
            // regardless of JVM locale. diagnostic.code (e.g. "compiler.err.cant.resolve.location")
            // is stable across all locales, unlike diagnostic.toString() or getMessage().
            item.errors.add(diagnostic.lineNumber to "${diagnostic.code} $message")
        }

        // compile files
        val platformFileSystems = mutableListOf<FileSystem>()
        val isSuccess = try {
            val androidJar = dependencies.firstOrNull { File(it).name == "android.jar" }
                ?.takeUnless { module.moduleType == ModuleInfo.Type.JavaLibrary }
            if (androidJar != null) {
                val isModularSource = (sourceVersion.removePrefix("1.").toIntOrNull() ?: 8) >= 9
                // Android does not provide StringConcatFactory; match AGP's StringBuilder concatenation.
                cmdOptions.add("-XDstringConcat=inline")
                try {
                    val frameworkJars = dependencies.take(dependencies.indexOf(androidJar)).map(::File)
                    val sdkPlatformJars = getAndroidPlatformJars(context, module, File(androidJar), isModularSource, logger)
                    val platformJars = if (isModularSource && sdkPlatformJars.any { it.path == androidJar }) {
                        frameworkJars + sdkPlatformJars
                    } else {
                        sdkPlatformJars
                    }
                    if (isModularSource) {
                        val platformPaths = platformJars.map { file ->
                            if (file.isDirectory) {
                                file.toPath()
                            } else {
                                // Bypass IDE providers that return the host file system for arbitrary paths.
                                val fileSystem = FileSystemProvider.installedProviders().first { it.scheme == "jar" }
                                    .newFileSystem(file.toPath(), emptyMap<String, Any>())
                                platformFileSystems.add(fileSystem)
                                fileSystem.getPath("/")
                            }
                        }
                        fileManager.setLocationForModule(StandardLocation.MODULE_PATH, "java.base", platformPaths)
                        logger.debug("Java platform java.base: $platformJars")
                        // Disable host modules only after the Android platform is ready.
                        cmdOptions.addAll(listOf("--system", "none"))
                    } else {
                        // Source 8 has no modules; the file-manager API also supports a newer bytecode target.
                        fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, frameworkJars + platformJars)
                        logger.debug("Java platform boot classpath: ${frameworkJars + platformJars}")
                    }
                } catch (e: Exception) {
                    logger.warn("Android Java platform isolation failed; " +
                            "using host JDK platform classes for this compilation", e)
                    closeCompilerResources(fileManager, platformFileSystems, logger)
                    fileManager = compiler.getStandardFileManager(null, null, null)
                    compileItems = task.files.map {
                        JavaCompileItem(it, fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first())
                    }
                }
            }
            logCompileCommand(module, cmdOptions, compileItems, logger)
            val javaTask = try {
                compiler.getTask(null, fileManager, compileListener, cmdOptions, null, compileItems.map { it.fileObject })
            } catch (e: Exception) {
                logger.warn("getTask failed", e)
                null
            }
            javaTask?.call() ?: false
        } finally {
            closeCompilerResources(fileManager, platformFileSystems, logger)
        }

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
                    // Non-class outputs generated by javac can belong to multiple APKs for library androidTest.
                    CompileOutput(
                        CompileOutput.Type.Res,
                        it,
                        task.outputDir,
                        apkPath = context.getBelongsApkPath(module) ?: "",
                        targetApkPaths = context.getAllBelongsApkPaths(module),
                    )
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
            if (options.generatedSourceDir == null) {
                outputs.forEach { it.file.copyToBaseDir(task.outputDir, javaClassPath) }
            }
            val generatedOutputs = options.generatedSourceDir?.let { root ->
                root.listFilesRecursively().filter { it.extension == "java" }
                    .map { CompileOutput(CompileOutput.Type.Java, it, root) }
            }.orEmpty()

            hasRecreateAfterInternalError = false
            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs + generatedOutputs)
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
            
            CompileResult(
                task,
                compileItems.map { item ->
                    if (forceCompilerOutputDebug) {
                        item.errors.forEach { (_, message) -> logger.warn(message) }
                    }
                    Result.failure(CompileError(
                        file = item.file,
                        errors = item.errors,
                        hasDirectSourceDiagnostic = item.errors.isNotEmpty(),
                    ))
                },
                emptyList(),
            )
        }
    }

    private fun closeCompilerResources(
        fileManager: StandardJavaFileManager,
        platformFileSystems: MutableList<FileSystem>,
        logger: Logger,
    ) {
        try {
            fileManager.close()
        } catch (e: Exception) {
            logger.warn("Close javac file manager failed", e)
        }
        platformFileSystems.asReversed().forEach {
            try {
                it.close()
            } catch (e: Exception) {
                logger.warn("Close Android platform file system failed: $it", e)
            }
        }
        platformFileSystems.clear()
    }

    /** Selects Android platform classes and the compiler-only lambda stubs used by AGP. */
    private fun getAndroidPlatformJars(
        context: ICompileContext,
        module: ModuleInfo,
        androidJar: File,
        isModularSource: Boolean,
        logger: Logger,
    ): List<File> {
        val systemModulesJar = File(androidJar.parentFile, "core-for-system-modules.jar")
        if (isModularSource && systemModulesJar.isFile) {
            return listOf(systemModulesJar)
        }
        val buildToolsDir = File(context.androidHome, "build-tools")
        val moduleLambdaStubs = module.buildToolsVersion?.let {
            File(buildToolsDir, "$it/core-lambda-stubs.jar")
        }
        val lambdaStubs = moduleLambdaStubs?.takeIf { it.isFile }
            ?: buildToolsDir.listFiles()?.map { File(it, "core-lambda-stubs.jar") }
                ?.filter { it.isFile }?.maxByOrNull { BuildToolsVersionComparator(it.parentFile.name) }
        if (lambdaStubs == null) {
            logger.debug("Android lambda stubs not found in $buildToolsDir; lambda compilation may fail")
        }
        return listOfNotNull(androidJar, lambdaStubs)
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
