package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.JuggInternalException
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
            // new classLoader by projectCompilerClasspath success, use it
            isUseProjectCompiler = true
            logger.debug("kotlin compiler type: project")
        } catch (e: Exception) {
            // projectCompilerClasspath is not available, use embedded compiler
            logger.debug("kotlin compiler type: embedded, reason: ${e.message}")
            if (!::classLoader.isInitialized || isUseProjectCompiler) {
                classLoader = getIsolateClassLoader(juggPluginClasspathUrls)
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

        val exitCodeClass = classLoader.loadClass("org.jetbrains.kotlin.cli.common.ExitCode")
        val exitCodeMethod = exitCodeClass.getDeclaredMethod("getCode")
        val exitCodeInt = exitCodeMethod.invoke(exitCodeIsolate)

        val exitCode = when(exitCodeInt) {
            0 -> ExitCode.OK
            1 -> ExitCode.COMPILATION_ERROR
            2 -> ExitCode.INTERNAL_ERROR
            3 -> ExitCode.SCRIPT_EXECUTION_ERROR
            else -> throw IllegalArgumentException("unexpected exit code $exitCodeInt")
        }

        return exitCode
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
