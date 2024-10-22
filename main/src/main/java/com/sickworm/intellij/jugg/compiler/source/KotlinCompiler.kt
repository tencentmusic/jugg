package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.RPackageReader
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.ide.JuggSettings
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File

class KotlinCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = false

    override val isNeedPrintProgress: Boolean = true

    private var hasRecreateAfterInternalError = false

    private var hasFoundKotlinAndroidExtensions: Boolean = false
    private var kotlinAndroidExtensionsPath: String? = null

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val dependencies = context.getModuleDependencies(module, task)

        val analyzeResult = analyzeSource(task.files.map { it.file }, module)
        logger.debug("analyzeSource result: $analyzeResult")

        if (analyzeResult.isNeedKotlinAndroidExtensions) {
            if (analyzeResult.rPackageName == null) {
                logger.warn("found KotlinAndroidExtensions, but rPackageName is null, failed to proceed.")
                val details: List<Result<CompileFile, CompileError>> = task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "rPackageName not found for KotlinAndroidExtensions")))
                }
                return CompileResult(task, details, emptyList())
            }
            if (!hasFoundKotlinAndroidExtensions) {
                val classLoader = this::class.java.classLoader
                kotlinAndroidExtensionsPath = if (classLoader is UrlClassLoader) {
                    // running in IDE
                    val filePath = classLoader.urls.first { it.file.contains("kotlin-android-extensions") }.file
                    filePath.replace("%20", " ")
                } else {
                    // running in test. notion: this may cost 500+ms which will affect compile cost
                    val filePath = ClassGraph().classpathFiles.first { it.name.startsWith("kotlin-android-extensions") }.path
                    filePath.replace("%20", " ")
                }
                hasFoundKotlinAndroidExtensions = true
            }
            if (kotlinAndroidExtensionsPath == null) {
                logger.warn("KotlinAndroidExtensions not found in classpath, can not proceed kotlin android extensions.")
                val details: List<Result<CompileFile, CompileError>> = task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "kotlinAndroidExtensionsPath not found in classpath")))
                }
                return CompileResult(task, details, emptyList())
            }
        }

        val kotlinClassPath = module.buildPathInfo.kotlinClassPath.absoluteFile

        val flavor = "main"
        // I'm not sure whether flavor should read in "MyApplication.app.main"
        // and module.name don't end with ".main" now, so just leave it default "main"
//        val splits = module.name.split(".")
//        if (splits.size >= 2) {
//            flavor = splits.last()
//        } else {
//            logger.warn("module name \"${module.name}\" is not valid, use default flavor name: $flavor")
//        }

        val resourcePaths: List<String> = task.files.flatMap {
            it.module.resourceDirs.map { file ->
                file.absolutePath
            }
        }

        val extensionArgs = if (analyzeResult.isNeedKotlinAndroidExtensions) {
            val variantArgs: List<String> = resourcePaths.flatMap { resourcePath ->
                listOf("-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}")
            }
            listOf(
                "-Xplugin=$kotlinAndroidExtensionsPath",
                "-P", "plugin:org.jetbrains.kotlin.android:package=${analyzeResult.rPackageName}",
                "-P", "plugin:org.jetbrains.kotlin.android:experimental=true",
            ) + variantArgs
        } else {
            emptyList()
        }

        val javaSourceRoots = module.sourceDirs + context.getGeneratedSourcePaths(module)

        var jvmTarget = module.kotlinJvmTarget ?: "1.8"
        if (jvmTarget == "1.6" || jvmTarget == "1.7") {
            logger.debug("jvm target is $jvmTarget, force to 1.8 to avoid error: " +
                    "error: JVM target 1.6 is no longer supported. Please migrate to JVM target 1.8 or above")
            logger.debug("please skip this check if using kotlin compile in project.")
            jvmTarget = "1.8"
        }

        val compileArgs = module.kotlinFreeCompilerArgs + listOf(
            "-verbose",
            "-language-version", guessKotlinVersion(module),
            "-jvm-target", jvmTarget,
            "-nowarn",
            "-no-stdlib",
            "-no-reflect",
            "-module-name", "${module.name}_${module.buildVariant}",
            "-Xfriend-paths=${kotlinClassPath.absolutePath}",
            "-Xallow-no-source-files",
            "-Xreport-output-files",
            // resolve "class is not abstract and does not implement abstract member"
            // resolve "reference not found" when invoke new java methods that haven't been compiled
            "-Xjava-source-roots=${javaSourceRoots.joinToString(",")}",
            // we have to set output dir to kotlin compiled class path to resolve
            // 'xxx' is a public API property declared in different module
            "-d", kotlinClassPath.absolutePath,
        )

        var classPathArgs = listOf<String>()
        if (dependencies.isNotEmpty()) {
            classPathArgs = listOf(
                "-cp", dependencies.joinToString(File.pathSeparator)
            )
        }

        val fileArgs = task.files.map { it.file.absolutePath }

        val command = extensionArgs + compileArgs + classPathArgs + fileArgs
        logCompileCommand(command)

        // resolve kotlin extension function unresolved reference
        val merger = KmModuleMergerForCompilation(kotlinClassPath)
        try {
            merger.loadAndMerge()
        } catch (e: Exception) {
            logger.debug("loadAndMerge .kotlin_module failed", e)
            logger.warn("Load and merge .kotlin_module failed, it may cause compile time error. Detail: ${e.message}")
        }

        val outputParser = KotlinCompilerOutputParser(task.files, logger)
        val exitCode = try {
            kotlinCompile.exec(outputParser.printStream, command.toTypedArray())
        } catch (e: Exception) {
            logger.error("invoke kotlin compile failed", e)
            ExitCode.INTERNAL_ERROR
        }
        outputParser.flush()
        logger.debug("kotlin compile result code: $exitCode")

        // retry strategy
        val errorResults = outputParser.results.sumOf {
            if (it.isSuccess) 0 else it.getFailure().errors.size
        }
        var shouldRecreate = false
        var retryReason = ""
        if (errorResults > JuggSettings.minErrorToRecreateCompiler) {
            // most likely kotlin compiler is not working, try to recreate once
            retryReason = "Kotlin compile failed with too many errors(> ${JuggSettings.minErrorToRecreateCompiler})"
            shouldRecreate = true
        }
        if (exitCode == ExitCode.INTERNAL_ERROR) {
            logger.warn("Kotlin compile failed with with INTERNAL_ERROR!")
            retryReason = "Kotlin compile failed with INTERNAL_ERROR"
            shouldRecreate = true
        }
        if (shouldRecreate) {
            logger.debug("try recreate compiler once, hasRecreateAfterInternalError: $hasRecreateAfterInternalError")
            if (!hasRecreateAfterInternalError) {
                logger.warn("\n$retryReason, retry with recreating compiler once.\n")
                hasRecreateAfterInternalError = true
                kotlinCompile = K2JVMCompilerIsolate()
                return doModuleCompile(task, module)
            }
        }

        try {
            merger.loadAndMerge()
            merger.save()
        } catch (e: Exception) {
            logger.debug("loadAndMerge .kotlin_module after compile failed", e)
            logger.warn("Load and merge .kotlin_module after compile failed, it may cause compile time error later. Detail: ${e.message}")
        }

        if (exitCode != ExitCode.OK) {
            return CompileResult(task, outputParser.results, emptyList())
        }

        // copy outputs to task.outputDir
        val outputs = outputParser.outputs.mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            val targetFile = it.copyToBaseDir(kotlinClassPath, task.outputDir)
            CompileOutput(CompileOutput.Type.Class, targetFile, task.outputDir)
        }

        hasRecreateAfterInternalError = false
        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }

    private fun logCompileCommand(options: List<String>) {
        val baseDir = context.projectDir

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
        logger.debug("kotlin compile base dir: $baseDir")
        logger.debug("kotlin compile: kotlinc ${shortOptions.joinToString(" ")}")
    }

    private fun analyzeSource(files: List<File>, module: ModuleInfo): KotlinSourceAnalyzeResult {
        val startTime = System.currentTimeMillis()
        var isNeedKotlinAndroidExtensions = false
        files.forEach root@{ file ->
            file.readLines().forEach {
                if (!it.startsWith("import")) {
                    return@forEach
                }
                if (it.startsWith("import kotlinx.android.synthetic.")) {
                    logger.debug("find kotlinx.android.synthetic in $file")
                    isNeedKotlinAndroidExtensions = true
                }
                if (isNeedKotlinAndroidExtensions) {
                    return@root
                }
            }
        }

        var rPackageName: String? = null
        if (isNeedKotlinAndroidExtensions && module.buildPathInfo.mergedManifest.exists()) {
            rPackageName = RPackageReader(module.buildPathInfo.mergedManifest, logger).readPackageName()
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("analyze kotlin source cost: $costTime ms")
        return KotlinSourceAnalyzeResult(isNeedKotlinAndroidExtensions, rPackageName)
    }

    private var guessKotlinVersionCache = mapOf<String, String>()


    private fun guessKotlinVersion(module: ModuleInfo): String {
        guessKotlinVersionCache[module.name]?.let {
            return it
        }

        val kotlinStdlibName = module.libraryDependencies.find {
            it.file.name.contains("kotlin-stdlib")
        }?.file?.name
        if (kotlinStdlibName == null) {
            logger.debug("kotlin-stdlib not found in module ${module.name}, can not guess kotlin version, use default ${K2JVMCompilerIsolate.VERSION}")
            return K2JVMCompilerIsolate.VERSION
        }

        val kotlinVersion = try {
            kotlinStdlibName.split("-").last().split(".").take(2).joinToString(".")
        } catch (e: Exception) {
            logger.debug("kotlin-stdlib name '$kotlinStdlibName' is not valid, can not guess kotlin version, use default ${K2JVMCompilerIsolate.VERSION}")
            return K2JVMCompilerIsolate.VERSION
        }

        if (kotlinVersion == "1.1" || kotlinVersion == "1.2" || kotlinVersion == "1.3") {
            logger.debug("guess kotlin version is $kotlinVersion, use min 1.4")
            return "1.4"
        }

        logger.debug("guess kotlin version: $kotlinVersion")
        return kotlinVersion
    }

    override fun warmUp() {
        val startTime = System.currentTimeMillis()
        val selectModule = context.modules.values.maxByOrNull {
            it.moduleDependencies.size + it.libraryDependencies.size
        }
        logger.debug("start KotlinCompiler warm up, selectModule: ${selectModule?.name}")
        doModuleCompile(CompileTask(emptyList(), context.tempCompileDir, CompileStatusHolder.DEFAULT), context.modules.values.first())
        logger.debug("finish KotlinCompiler warm up, cost: ${System.currentTimeMillis() - startTime}ms")
    }

    companion object {
        private var kotlinCompile = K2JVMCompilerIsolate()
    }
}

private data class KotlinSourceAnalyzeResult(
    val isNeedKotlinAndroidExtensions: Boolean,
    val rPackageName: String?,
)
