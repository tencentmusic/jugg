package com.sickworm.intellij.aidp.compiler.source

import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.listFilesRecursively
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class KotlinCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = true

    private val kotlinCompile = K2JVMCompiler()

    private var kotlinAndroidExtensionsPath: String? = null

    override fun doCompile(task: CompileTask): CompileResult {
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()

        if (kotlinAndroidExtensionsPath == null) {
            val classLoader = this::class.java.classLoader
            kotlinAndroidExtensionsPath = if (classLoader is UrlClassLoader) {
                // running in IDE
                classLoader.urls.first { it.file.contains("kotlin-android-extensions") }.file
            } else {
                // running in test. notion: this may cost 500+ms which will effect compile cost
                ClassGraph().classpathFiles.first { it.name.startsWith("kotlin-android-extensions") }.path
            }
        }

        // TODO read from project
        val packageName = "com.tencent.wesing.camerasource.example"
        val flavor = "main"
        val resourcePath = "/Users/wormchen/IdeaProjects/TMEVideoRecord/app/src/main/res"
        val command = mutableListOf<String>(
            "-Xplugin=$kotlinAndroidExtensionsPath",
            "-P", "plugin:org.jetbrains.kotlin.android:package=${packageName}",
            "-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}",
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-d", task.outputDir.absolutePath,
        )
        if (dependencies.isNotEmpty()) {
            command.add("-cp")
            command.add(dependencies.joinToString(File.pathSeparator))
        }
        command.add(task.files.joinToString(separator = " ") { it.file.absolutePath })
        kotlinCompile.exec(printStream, *command.toTypedArray())
        logger.debug("compile: ${String(outputStream.toByteArray())}")

        // TODO check error
        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}
