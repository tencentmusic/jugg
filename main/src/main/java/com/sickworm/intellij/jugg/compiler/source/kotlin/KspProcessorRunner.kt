package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader

/** Runs project KSP engines for fresh aggregate generation without consuming Gradle outputs. */
class KspProcessorRunner {
    fun generate(context: ICompileContext, module: ModuleInfo, sources: List<File>,
        processorPath: List<File>, processorNames: List<String>, options: Map<String, String>, work: File): File {
        check(work.deleteRecursively() && work.mkdirs()) { "Cannot prepare KSP workspace $work" }
        val generated = File(work, "sources").apply { mkdirs() }
        val files = sources.distinctBy { it.canonicalPath }
        val task = CompileTask(files.map { source ->
            CompileFile(if (source.extension == "kt") CompileFile.Type.Kotlin else CompileFile.Type.Java,
                source, source.parentFile, module)
        }, File(work, "classes"), CompileStatusHolder.DEFAULT)
        val libraries = context.getModuleDependencies(module, task).map(::File)
        val runtime = (module.kspInfo?.runtimeDependencies.orEmpty().map { it.file } + module.kotlinPlugins.orEmpty()).distinct()
        val ksp2 = module.kspInfo?.useKsp2 ?: runtime.any { it.name.startsWith("symbol-processing-aa-embeddable-") }
        try {
            if (ksp2) generateKsp2(context, module, files, libraries, runtime, processorPath, processorNames, options, work)
            else generateKsp1(context, module, files, libraries, runtime, processorPath, processorNames, options, work)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        return generated
    }

    private fun commonSources(module: ModuleInfo, sources: List<File>): List<File> = sources.filter { source ->
        module.kotlinCommonSourceDirs.any { source.canonicalFile.toPath().startsWith(it.canonicalFile.toPath()) }
    }

    /** KSP1 remains a compiler plugin and uses the existing isolated project Kotlin compiler. */
    private fun generateKsp1(context: ICompileContext, module: ModuleInfo, sources: List<File>, libraries: List<File>,
        runtime: List<File>, processors: List<File>, names: List<String>, options: Map<String, String>, work: File) {
        val compiler = K2JVMCompilerIsolate()
        compiler.initIfNeeded(runtime, context.logger)
        check(compiler.isUseProjectCompiler) { "Project Kotlin compiler is required for KSP1 in ${module.name}" }
        val plugins = runtime.filter { it.name.startsWith("symbol-processing-") || it.name.startsWith("symbol-processing-api-") }
        check(plugins.any { it.name.startsWith("symbol-processing-") && !it.name.startsWith("symbol-processing-api-") }) {
            "KSP1 compiler plugin is missing for ${module.name}"
        }
        val values = linkedMapOf("projectBaseDir" to module.projectRootDir.path, "kspOutputDir" to work.resolve("sources").path,
            "classOutputDir" to work.resolve("classes").path, "javaOutputDir" to work.resolve("sources/java").path,
            "kotlinOutputDir" to work.resolve("sources/kotlin").path, "resourceOutputDir" to work.resolve("resources").path,
            "cachesDir" to work.resolve("caches").path, "apclasspath" to processors.joinToString(File.pathSeparator) { it.path },
            "processorNames" to names.joinToString(","), "incremental" to "false", "withCompilation" to "false",
            "returnOkOnError" to "false")
        // KSP1 uses the classic frontend even when its compiler artifact is Kotlin 2.x.
        val compilerVersion = runtime.firstOrNull { it.name.startsWith("kotlin-compiler-embeddable-") }
            ?.name?.removePrefix("kotlin-compiler-embeddable-")?.split('.')?.take(2)?.joinToString(".")
        val language = (module.kspInfo?.languageVersion ?: compilerVersion)?.takeIf { it.startsWith("1.") } ?: "1.9"
        val api = module.kspInfo?.apiVersion?.takeIf { it.startsWith("1.") } ?: language
        val args = mutableListOf("-no-stdlib", "-no-reflect", "-Xallow-no-source-files", "-language-version", language,
            "-api-version", api, "-module-name", module.gradleModuleName ?: module.name,
            "-jvm-target", module.kotlinJvmTarget ?: "1.8",
            "-classpath", libraries.joinToString(File.pathSeparator) { it.path }, "-d", work.resolve("classes").path)
        args += plugins.map { "-Xplugin=${it.path}" }
        values.forEach { (key, value) -> args += listOf("-P", "plugin:com.google.devtools.ksp.symbol-processing:$key=$value") }
        options.forEach { (key, value) -> args += listOf("-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=$key=$value") }
        val common = commonSources(module, sources)
        if (common.isNotEmpty()) args += listOf("-Xmulti-platform", "-Xcommon-sources=${common.joinToString(",") { it.path }}")
        args += sources.map { it.path }
        val diagnostics = File(work, "diagnostics.txt")
        val exit = PrintStream(diagnostics).use { compiler.exec(it, args.toTypedArray()) }
        diagnostics.readLines().filter { it.isNotBlank() }.forEach { context.logger.info(it) }
        check(exit == ExitCode.OK) { "KSP1 generation failed for ${module.name}: $exit" }
    }

    /** KSP2 owns its Analysis API environment; only JDK values cross the isolated loader boundary. */
    private fun generateKsp2(context: ICompileContext, module: ModuleInfo, sources: List<File>, libraries: List<File>,
        runtime: List<File>, processors: List<File>, names: List<String>, options: Map<String, String>, work: File) {
        val paths = (runtime + processors).distinct()
        check(runtime.any { it.name.startsWith("symbol-processing-aa-embeddable-") }) {
            "KSP2 Analysis API engine is missing for ${module.name}"
        }
        URLClassLoader(paths.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = loader
            try {
                executeKsp2(context, module, loader, names,
                    ksp2Config(loader, module, sources, libraries, options, work))
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    /** Executes selected providers and retains native diagnostics when the engine reports failure. */
    private fun executeKsp2(context: ICompileContext, module: ModuleInfo, loader: ClassLoader,
        names: List<String>, config: Any) {
        val loggerType = loader.loadClass("com.google.devtools.ksp.processing.KSPLogger")
        var hasErrors = false
        var processorFailure: Throwable? = null
        val logger = Proxy.newProxyInstance(loader, arrayOf(loggerType)) { _, method, arguments ->
            when (method.name) {
                "error" -> { hasErrors = true; context.logger.warn("KSP2: ${arguments?.firstOrNull()}") }
                "exception" -> {
                    hasErrors = true
                    processorFailure = arguments?.firstOrNull() as? Throwable
                    context.logger.warn("KSP2 processor exception", processorFailure)
                }
                "warn" -> context.logger.warn("KSP2: ${arguments?.firstOrNull()}")
                "info", "logging" -> context.logger.info("KSP2: ${arguments?.firstOrNull()}")
            }
            null
        }
        val providers = names.map { loader.loadClass(it).getConstructor().newInstance() }
        val engine = loader.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
            .getConstructor(loader.loadClass("com.google.devtools.ksp.processing.KSPConfig"), List::class.java, loggerType)
            .newInstance(config, providers, logger)
        val exit = engine.javaClass.getMethod("execute").invoke(engine)
        if (exit.toString() != "OK" || hasErrors) {
            throw IllegalStateException("KSP2 generation failed for ${module.name}: $exit", processorFailure)
        }
    }

    private fun ksp2Config(loader: ClassLoader, module: ModuleInfo, sources: List<File>, libraries: List<File>,
        options: Map<String, String>, work: File): Any {
        val builder = loader.loadClass("com.google.devtools.ksp.processing.KSPJvmConfig\$Builder").getConstructor().newInstance()
        val kotlinVersion = loader.loadClass("kotlin.KotlinVersion").getField("CURRENT").get(null).toString()
            .split('.').take(2).joinToString(".")
        val values = mapOf("ModuleName" to (module.gradleModuleName ?: module.name), "SourceRoots" to sources.filter { it.extension == "kt" },
            "JavaSourceRoots" to sources.filter { it.extension == "java" }, "CommonSourceRoots" to commonSources(module, sources),
            "Libraries" to libraries, "ProcessorOptions" to options, "ProjectBaseDir" to module.projectRootDir,
            "OutputBaseDir" to work.resolve("sources"), "CachesDir" to work.resolve("caches"),
            "ClassOutputDir" to work.resolve("classes"), "JavaOutputDir" to work.resolve("sources/java"),
            "KotlinOutputDir" to work.resolve("sources/kotlin"), "ResourceOutputDir" to work.resolve("resources"),
            "LanguageVersion" to (module.kspInfo?.languageVersion ?: kotlinVersion),
            "ApiVersion" to (module.kspInfo?.apiVersion ?: module.kspInfo?.languageVersion ?: kotlinVersion),
            "JdkHome" to File(System.getProperty("java.home")), "JvmTarget" to (module.kotlinJvmTarget ?: "1.8"), "Incremental" to false)
        values.forEach { (name, value) -> builder.javaClass.methods.single { it.name == "set$name" }.invoke(builder, value) }
        return builder.javaClass.getMethod("build").invoke(builder)
    }
}
