package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.JuggInternalException
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader

/**
 * Invoke K2JVMCompiler in isolate class loader, for:
 * 1. fix "Exception while analyzing expression" caused by "java.lang.NullPointerException at
 * com.intellij.psi.impl.source.ClassInnerStuffCache.getJavaClassName(ClassInnerStuffCache.java:211)"
 * because PsiClassImpl is "com.intellij.psi.impl.source.PsiClassImpl" instead of
 * "org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiClassImpl"
 *
 * 2. avoid using wrong version of K2JVMCompiler which embedded in IntelliJ Idea
 *
 */
class K2JVMCompilerIsolate {

    var isUseProjectCompiler: Boolean = false
        private set

    private lateinit var classLoader: URLClassLoader
    val currentCompiler: String?
        get() {
            if (!::classLoader.isInitialized) {
                return null
            }
            // why use parent ? see getIsolateClassLoader
            val urls = (classLoader.parent as? URLClassLoader)?.urLs ?: return null
            return getCompilerName(urls.toList())
        }

    /**
     * Init [classLoader] which is used to load K2JVMCompiler.
     * Priority use project compiler classpath, if not available use embedded compiler
     */
    @Synchronized
    fun initIfNeeded(projectCompilerClasspath: List<File>?, logger: Logger) {
        if (!JuggSettings.isUseProjectKotlinCompiler) {
            logger.debug("kotlin compiler use embedded compiler by user setting")
            if (!::classLoader.isInitialized || isUseProjectCompiler) {
                classLoader = getIsolateClassLoader(juggPluginClasspathUrls)
                KotlinCompilerHostCompat.ensureShadedJavaVersionSupported(classLoader, logger)
            }
            isUseProjectCompiler = false
            return
        }

        try {
            val projectCompilerClasspathUrls = projectCompilerClasspath?.map { it.toURI().toURL() } ?: emptyList()
            val currentCompiler = if (isUseProjectCompiler) currentCompiler else null
            val expectCompiler = getCompilerName(projectCompilerClasspathUrls)
            if (currentCompiler != null && currentCompiler == expectCompiler) {
                // no need to renew classLoader
                logger.debug("kotlin compiler reuse, isUseProjectCompiler $isUseProjectCompiler, compiler: $currentCompiler")
                return
            }

            logger.debug("compiler not match, currentCompiler: $currentCompiler, expectCompiler: $expectCompiler. try renew classLoader")
            classLoader = getIsolateClassLoader(projectCompilerClasspathUrls, isAllIncluded = true)
            KotlinCompilerHostCompat.ensureShadedJavaVersionSupported(classLoader, logger)
            // new classLoader by projectCompilerClasspath success, use it
            isUseProjectCompiler = true
            logger.debug("kotlin compiler type: project")
        } catch (e: Exception) {
            // projectCompilerClasspath is not available, use embedded compiler
            logger.debug("kotlin compiler type: embedded, reason: ${e.message}")
            if (!::classLoader.isInitialized || isUseProjectCompiler) {
                classLoader = getIsolateClassLoader(juggPluginClasspathUrls)
                KotlinCompilerHostCompat.ensureShadedJavaVersionSupported(classLoader, logger)
            }
            isUseProjectCompiler = false
        }
    }

    @Suppress("MoveVariableDeclarationIntoWhen")
    @Synchronized
    fun exec(printStream: PrintStream, args: Array<String>): ExitCode {
        val compileClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compileInstance = compileClass.declaredConstructors[0].newInstance()
        val method = compileClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
        val exitCodeIsolate = try {
            method.invoke(compileInstance, printStream, args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Unwrap so the real cause (e.g. OutOfMemoryError) propagates instead of being
            // hidden behind "unexpected exit code NNN".
            throw e.cause ?: e
        }

        return toExitCode(exitCodeIsolate)
    }

    /** Result of an in-process compile with the project compiler's expect/actual tracker. */
    internal class ExpectActualTrackingResult(
        val exitCode: ExitCode,
        val expectToActual: Map<File, Set<File>>,
        internal val tracker: Any,
    )

    /** Compiles with incremental expect/actual tracking enabled in the active project compiler. */
    @Synchronized
    internal fun execWithExpectActualTracking(printStream: PrintStream, args: Array<String>): ExpectActualTrackingResult {
        val compilerClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compiler = compilerClass.declaredConstructors[0].newInstance()
        val compilerArguments = compilerClass.getMethod("createArguments").invoke(compiler)
        compilerClass.methods.first { it.name == "parseArguments" && it.parameterCount == 2 }
            .invoke(compiler, args, compilerArguments)
        compilerArguments.javaClass.methods.first {
            it.name == "setIncrementalCompilation" && it.parameterCount == 1
        }.invoke(compilerArguments, true)

        val tracker = classLoader.loadClass("org.jetbrains.kotlin.incremental.ExpectActualTrackerImpl")
            .getConstructor().newInstance()
        val services = createServicesWithTracker(tracker)
        val collector = createMessageCollector(printStream)
        val exitCodeIsolate = try {
            compilerClass.methods.first {
                it.name == "exec" && it.parameterCount == 3 &&
                    it.parameterTypes[0].name.endsWith("MessageCollector")
            }.invoke(compiler, collector, services, compilerArguments)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
        @Suppress("UNCHECKED_CAST")
        val relations = tracker.javaClass.getMethod("getExpectToActualMap").invoke(tracker) as Map<File, Set<File>>
        return ExpectActualTrackingResult(
            toExitCode(exitCodeIsolate),
            relations.mapKeys { it.key.canonicalFile }
                .mapValues { (_, files) -> files.map(File::getCanonicalFile).toSet() },
            tracker,
        )
    }

    private fun createServicesWithTracker(tracker: Any): Any {
        val builder = classLoader.loadClass("org.jetbrains.kotlin.config.Services\$Builder")
            .getConstructor().newInstance()
        val trackerInterface = classLoader.loadClass(
            "org.jetbrains.kotlin.incremental.components.ExpectActualTracker"
        )
        builder.javaClass.getMethod("register", Class::class.java, Any::class.java)
            .invoke(builder, trackerInterface, tracker)
        return builder.javaClass.getMethod("build").invoke(builder)
    }

    private fun createMessageCollector(printStream: PrintStream): Any {
        val rendererClass = classLoader.loadClass("org.jetbrains.kotlin.cli.common.messages.MessageRenderer")
        val renderer = rendererClass.getField("PLAIN_FULL_PATHS").get(null)
        val collectorClass = classLoader.loadClass(
            "org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector"
        )
        return collectorClass.getConstructor(PrintStream::class.java, rendererClass, Boolean::class.javaPrimitiveType)
            .newInstance(printStream, renderer, false)
    }

    private fun toExitCode(exitCodeIsolate: Any): ExitCode {
        val exitCodeInt = exitCodeIsolate.javaClass.getDeclaredMethod("getCode").invoke(exitCodeIsolate)
        return when(exitCodeInt) {
            0 -> ExitCode.OK
            1 -> ExitCode.COMPILATION_ERROR
            2 -> ExitCode.INTERNAL_ERROR
            3 -> ExitCode.SCRIPT_EXECUTION_ERROR
            else -> throw IllegalArgumentException("unexpected exit code $exitCodeInt")
        }
    }

    /** Reads Kotlin incremental cache relations using the active project compiler's internal API. */
    @Synchronized
    fun readComplementaryFiles(
        cacheRoot: File,
        projectRoot: File,
        outputDir: File,
        sourceFiles: List<File>,
    ): List<File> {
        val cache = openIncrementalCache(cacheRoot, projectRoot, outputDir)
        return try {
            @Suppress("UNCHECKED_CAST")
            cache.javaClass.getMethod("getComplementaryFilesRecursive", Collection::class.java)
                .invoke(cache, sourceFiles) as Collection<File>
        } finally {
            cache.javaClass.getMethod("close").invoke(cache)
        }.toList()
    }

    /** Resolves baseline class outputs owned by the given Kotlin sources. */
    @Synchronized
    fun readSourceOutputs(
        cacheRoot: File,
        projectRoot: File,
        outputDir: File,
        sourceFiles: List<File>,
    ): List<File> {
        val cache = openIncrementalCache(cacheRoot, projectRoot, outputDir)
        return try {
            val classes = cache.javaClass.getMethod("classesBySources", Iterable::class.java)
                .invoke(cache, sourceFiles) as Iterable<*>
            classes.mapNotNull { className ->
                val internalName = className?.javaClass?.getMethod("getInternalName")?.invoke(className) as? String
                    ?: return@mapNotNull null
                val path = cache.javaClass.getMethod("getClassFilePath", String::class.java)
                    .invoke(cache, internalName) as? String
                path?.let(::File)?.takeIf { it.extension == "class" && it.exists() }
            }
        } finally {
            cache.javaClass.getMethod("close").invoke(cache)
        }.distinctBy { it.canonicalPath }
    }

    /** Updates complementary relations using a tracker produced by this isolated compiler. */
    @Synchronized
    internal fun updateComplementaryFiles(
        cacheRoot: File,
        projectRoot: File,
        outputDir: File,
        dirtyFiles: List<File>,
        tracking: ExpectActualTrackingResult,
    ) {
        val cache = openIncrementalCache(cacheRoot, projectRoot, outputDir)
        try {
            cache.javaClass.methods.first {
                it.name == "updateComplementaryFiles" && it.parameterCount == 2
            }.invoke(cache, dirtyFiles, tracking.tracker)
            cache.javaClass.methods.firstOrNull { it.name == "flush" && it.parameterCount == 0 }
                ?.invoke(cache)
        } finally {
            cache.javaClass.getMethod("close").invoke(cache)
        }
    }

    private fun openIncrementalCache(cacheRoot: File, projectRoot: File, outputDir: File): Any {
        val converterClass = classLoader.loadClass(
            "org.jetbrains.kotlin.incremental.storage.RelocatableFileToPathConverter"
        )
        val converter = converterClass.getConstructor(File::class.java).newInstance(projectRoot)
        val transaction = classLoader.loadClass("org.jetbrains.kotlin.incremental.NonRecoverableCompilationTransaction")
            .getConstructor().newInstance()
        val reporter = classLoader.loadClass("org.jetbrains.kotlin.build.report.DoNothingICReporter")
            .getField("INSTANCE").get(null)
        val contextClass = classLoader.loadClass("org.jetbrains.kotlin.incremental.IncrementalCompilationContext")
        val context = contextClass.constructors.single { it.parameterCount == 6 }.newInstance(
            converter, false, transaction, reporter, false, false,
        )
        val cacheClass = classLoader.loadClass("org.jetbrains.kotlin.incremental.IncrementalJvmCache")
        return createIncrementalCache(cacheClass, contextClass, context, cacheRoot, outputDir)
    }

    private fun createIncrementalCache(
        cacheClass: Class<*>,
        contextClass: Class<*>,
        context: Any,
        cacheRoot: File,
        outputDir: File,
    ): Any {
        cacheClass.constructors.firstOrNull { it.parameterCount == 3 }?.let {
            return it.newInstance(cacheRoot, context, outputDir)
        }
        val subtypeTrackerClass = classLoader.loadClass("org.jetbrains.kotlin.incremental.components.SubtypeTracker")
        val subtypeTracker = classLoader.loadClass(
            "org.jetbrains.kotlin.incremental.components.SubtypeTracker\$DoNothing"
        ).getField("INSTANCE").get(null)
        return cacheClass.getConstructor(File::class.java, contextClass, File::class.java, subtypeTrackerClass)
            .newInstance(cacheRoot, context, outputDir, subtypeTracker)
    }

    companion object {

        const val VERSION = "1.7"

        private const val KOTLIN_COMPILER_NAME = "kotlin-compiler-embeddable"

        private val requiredLibraries = setOf(
            "annotations",
            "kotlin-compiler-embeddable",
            // "trove4j", // trove4j is not in Kotlin 2.2 classpath, and I just skip checking it for all Kotlin versions
            "kotlin-reflect",
            "kotlin-stdlib",
        )

        /**
         * Classpath of Jugg, which includes kotlin compiler and its dependencies
         */
        private val juggPluginClasspathUrls by lazy { ClassGraph().classpathURLs }

        private fun getIsolateClassLoader(urls: List<URL>, isAllIncluded: Boolean = false): URLClassLoader {
            val libraryClasspath = filterCompilerLibraries(urls)
            val missingClasspath = getMissingClasspath(libraryClasspath)
            if (missingClasspath.isNotEmpty()) {
                throw JuggInternalException.initKotlinCompilerFailed(missingClasspath)
            }
            val finalLibraryClasspath = if (isAllIncluded) urls else libraryClasspath

            // missing tools.jar, find it in origin class loader
//            return URLClassLoader(libraryClasspath.toTypedArray(), this::class.java.classLoader)
            // set parent will load K2JVMCompiler in parent class loader, which will cause class conflict in execution
            val loader = PriorityURLClassLoader(finalLibraryClasspath.toTypedArray(), lowPriorityParent = this::class.java.classLoader)
            // it's wired that kapt class loading will use parent class loader that Jugg provided, so wrap it with an empty class loader
            return URLClassLoader(emptyArray(), loader)
        }

        private fun getMissingClasspath(libraryClasspath: List<URL>): List<String> {
            return requiredLibraries.filter { libraryName ->
                !libraryClasspath.any {
                    File(it.file).name.startsWith(libraryName) && File(it.file).name.endsWith(".jar")
                }
            }
        }

        fun getKotlinCompilerVersion(projectCompilerClasspath: List<File>?): String? {
            val urls = projectCompilerClasspath?.map { it.toURI().toURL() } ?: return null
            val missingClasspath = getMissingClasspath(urls)
            if (missingClasspath.isNotEmpty()) {
                // can not use as kotlin compiler
                return null
            }
            return getCompilerName(urls)
        }

        /**
         * @return all libraries that are required by kotlin compiler, plugins and other dependencies are not included
         */
        private fun filterCompilerLibraries(allClasspath: Collection<URL>): List<URL> {
            return allClasspath
                .filter {
                    it.isCompilerLibrary
                }.distinctBy {
                    it.file
                }
        }

        private val URL.isCompilerLibrary: Boolean get() {
            val name = File(file).name
            return requiredLibraries.any {
                name.startsWith(it) && name.endsWith(".jar")
            }
        }

        private fun getCompilerName(urls: List<URL>): String? {
            val compilerDependency = urls.find { File(it.file).name.startsWith(KOTLIN_COMPILER_NAME) }?.file ?: return null
            return File(compilerDependency).name
        }
    }
}
