package com.sickworm.intellij.jugg.compiler.source.kotlin

import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiles the supplied project processor once, independently of Android/Gradle project builds. */
internal object ARouterKspFixture {
    val compiler: List<File> get() = paths("compiler")
    val processorDependencies: List<File> get() = paths("processor.dependencies")
    fun runtime(ksp2: Boolean): List<File> = paths(if (ksp2) "runtime2" else "runtime1")
    const val provider = "com.alibaba.android.arouter.ksp.ARouterSymbolProcessorProvider"
    private fun paths(name: String) = System.getProperty("ksp.test.$name").split(File.pathSeparator).map(::File)

    val annotation: File by lazy {
        val dir = File("build/ksp-fixture/annotation").apply { deleteRecursively(); mkdirs() }
        val sources = File("src/test/assets/arouter-ksp/arouter-annotation/java").walkTopDown()
            .filter { it.extension == "java" }.toList()
        ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null).use { manager ->
            assertTrue(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                listOf("-proc:none", "-d", dir.path), null, manager.getJavaFileObjectsFromFiles(sources)).call())
        }
        jar(dir, File("build/ksp-fixture/annotation.jar"))
    }

    val processor: File by lazy {
        val dir = File("build/ksp-fixture/processor").apply { deleteRecursively(); mkdirs() }
        val sources = File("src/test/assets/arouter-ksp/arouter-ksp/kotlin").walkTopDown()
            .filter { it.extension == "kt" }.toList()
        compile(sources + File("src/test/assets/arouter-ksp/test-providers/UnrelatedProvider.kt"), processorDependencies + annotation, dir)
        val spi = File(dir, "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")
        spi.parentFile.mkdirs()
        spi.writeText(provider + "\nfixture.UnrelatedProvider\n")
        jar(dir, File("build/ksp-fixture/processor.jar"))
    }

    fun compile(sources: List<File>, libraries: List<File>, output: File) {
        URLClassLoader(compiler.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            val args = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath",
                (libraries + compiler).joinToString(File.pathSeparator) { it.path }, "-d", output.path) + sources.map { it.path }
            val exit = type.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
                .invoke(type.getConstructor().newInstance(), System.out, args.toTypedArray())
            assertEquals("OK", exit.toString())
        }
    }

    private fun jar(dir: File, target: File): File {
        JarOutputStream(target.outputStream()).use { output ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                output.putNextEntry(JarEntry(file.relativeTo(dir).invariantSeparatorsPath))
                output.write(file.readBytes())
                output.closeEntry()
            }
        }
        return target
    }
}
