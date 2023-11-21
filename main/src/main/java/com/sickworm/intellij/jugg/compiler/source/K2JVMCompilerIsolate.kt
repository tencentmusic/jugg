package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.diagnostic.Logger
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

    private lateinit var classLoader: ClassLoader

    @Suppress("MoveVariableDeclarationIntoWhen")
    fun exec(printStream: PrintStream, args: Array<String>): ExitCode {
        if (!::classLoader.isInitialized) {
            classLoader = getIsolateClassLoader()
        }

        val compileClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compileInstance = compileClass.declaredConstructors[0].newInstance()
        val method = compileClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
        val exitCodeIsolate = method.invoke(compileInstance, printStream, args)

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

        private val requiredLibraries = listOf(
            "annotations-13.0.jar", // as plugin
            "annotations-23.0.0.jar", // in test
            "kotlin-compiler-embeddable-1.7.22.jar",
            "trove4j-1.0.20200330.jar",
            "kotlin-reflect-1.7.22.jar",
            "kotlin-stdlib-1.7.22.jar",
            "kotlin-stdlib-common-1.7.22.jar",
            "kotlin-stdlib-jdk7-1.7.22.jar",
            "kotlin-stdlib-jdk8-1.7.22.jar"
        )
        private val exceptLibrariesSize = requiredLibraries.size - 1 // two annotations inside

        private fun getIsolateClassLoader(): ClassLoader {
            val allClasspath = ClassGraph().classpathURLs
            val libraryClasspath = mutableMapOf<String, URL>()
            allClasspath.forEach {
                val name = File(it.file).name
                if (!requiredLibraries.contains(name)) {
                    return@forEach
                }
                if (libraryClasspath.keys.contains(name)) {
                    return@forEach
                }
                libraryClasspath[name] = it
            }
            if (libraryClasspath.size != exceptLibrariesSize) {
                val missingClasspath = requiredLibraries.filter { libraryName ->
                    !libraryClasspath.keys.contains(libraryName)
                }
                throw JuggInternalException.initKotlinCompilerFailed(missingClasspath)
            }

            return URLClassLoader(libraryClasspath.values.toTypedArray(), null)
        }
    }
}
