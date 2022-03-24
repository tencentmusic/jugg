package com.sickworm.intellij.jugg.compiler.source

import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.*
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
        // split by module
        val files = task.files.groupBy { it.module.name }
        val results = files.map {
            doModuleCompile(CompileTask(it.value, task.outputDir), it.value[0].module)
        }
        if (results.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }
        return results.reduce { acc, compileResult -> acc + compileResult }
    }

    private fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
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
            "-module-name", "${module.name}_${context.variant}",
            "-Xfriend-paths=${module.buildPathInfo.kotlinClassPath}",
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

        // TODO more elegant to check error?
        var hasError = false
        var isError = true
        val message = StringBuilder()
        var isNewMessage = false
        outputString.split("\n").forEach {
            if (it.contains("warning:")) {
                isNewMessage = true
            } else if (it.contains("error:")) {
                isNewMessage = true
                hasError = true
            }

            if (isNewMessage && message.isNotEmpty()) {
                isNewMessage = false
                if (isError) {
                    logger.error(message.toString())
                } else {
                    logger.warn(message.toString())
                }
                message.clear()
            }

            if (message.isNotEmpty()) {
                message.appendLine()
            }
            message.append(it)
            if (it.contains("warning:")) {
                isError = false
            } else if (it.contains("error:")) {
                isError = true
            }
        }

        if (hasError) {
            return CompileResult(task, task.files.map {
                // TODO split error
                Result.failure(CompileError(it, listOf(0L to outputString)))
            }, emptyList())
        }

        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }
}
