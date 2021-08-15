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

        val packageName = context.packageName
        // TODO read flavor from sourceSets
        val flavor = "main"
        val resourcePaths: List<String> = task.files.flatMap {
            it.module.resourceDirs.map { file ->
                file.absolutePath
            }
        }
        val variantArgs: List<String> = resourcePaths.flatMap { resourcePath ->
            listOf("-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}")
        }
        val extensionArgs = listOf(
            "-Xplugin=$kotlinAndroidExtensionsPath",
            "-P", "plugin:org.jetbrains.kotlin.android:package=${packageName}",
        ) + variantArgs

        val compileArgs = listOf(
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-d", task.outputDir.absolutePath,
        )

        var classPathArgs = listOf<String>()
        if (dependencies.isNotEmpty()) {
            classPathArgs = listOf(
                "-cp", dependencies.joinToString(File.pathSeparator)
            )
        }

        val fileArgs = task.files.map { it.file.absolutePath }

        val command = extensionArgs + compileArgs + classPathArgs + fileArgs
        logger.debug("kotlin compile: kotlinc ${command.joinToString(" ")}")

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)
        kotlinCompile.exec(printStream, *command.toTypedArray())
        val outputString = String(outputStream.toByteArray())
        if (outputString.isNotEmpty()) {
            logger.warn("kotlin compile: $outputString")
        }

        // TODO check error
        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}
