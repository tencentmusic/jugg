package com.sickworm.intellij.aidp.compiler.source

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.listFilesRecursively
import io.github.classgraph.ClassGraph
import java.io.*
import java.net.URL
import java.net.URLClassLoader

class KotlinCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    private val kotlinCompile = KotlinCompileIsolate()

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)
        task.outputDir.mkdirs()

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        // TODO read from environment
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        val command = mutableListOf<String>(
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-d", task.outputDir.absolutePath
        )
        if (dependencies.isNotEmpty()) {
            command.add("-cp")
            command.add(dependencies.joinToString(File.pathSeparator))
        }
        command.add(task.files.joinToString(separator = " ") { it.file.absolutePath })
        println(command)
        kotlinCompile.exec(printStream, command.toTypedArray())
        logger.warn("compile: ${String(outputStream.toByteArray())}")

        // TODO check error
        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}

/**
 * invoke K2JVMCompiler in isolate class loader
 */
class KotlinCompileIsolate {

    private val classLoader: ClassLoader = getIsolateClassLoader()

    fun exec(printStream: PrintStream, args: Array<String>) {
        try {
            val compileClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            val compileInstance = compileClass.declaredConstructors[0].newInstance()
            val method = compileClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
            method.invoke(compileInstance, printStream, args)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    companion object {

        private val requiredLibraries = listOf(
            "annotations-13.0.jar", // plugin
            "annotations-19.0.0.jar", // test
            "kotlin-reflect-1.4.32.jar",
            "kotlin-stdlib-1.4.32.jar",
            "kotlin-stdlib-common-1.4.32.jar",
            "kotlin-stdlib-jdk7-1.4.32.jar",
            "kotlin-stdlib-jdk8-1.4.32.jar"
        )

        private val embeddedLibraries = listOf(
            "kotlin_compile/kotlin-compiler-1.4.32.jar",
            "kotlin_compile/trove4j-1.0.20181211.jar",
        )

        private fun getIsolateClassLoader(): ClassLoader {
            val libraryClasspath: List<URL> = ClassGraph().classpathURLs.filter {
                requiredLibraries.contains(File(it.file).name)
            }
            val saveDir = File(PathManager.getSystemPath(), "aidp")
            val compilerClasspath = embeddedLibraries.map {
                val outputJar = File(saveDir, it)
                if (!outputJar.exists()) {
                    outputJar.parentFile.mkdirs()
                    this::class.java.classLoader.getResource(it)!!.openStream().use { ins ->
                        FileOutputStream(outputJar).use { ous ->
                            ins.copyTo(ous)
                        }
                    }
                }

                outputJar.toURI().toURL()
            }

            return URLClassLoader((libraryClasspath + compilerClasspath).toTypedArray(), null)
        }
    }
}
