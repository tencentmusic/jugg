package com.sickworm.intellij.jugg.compiler.source

import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.jugg.compiler.*
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File

class KotlinCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = false

    private val kotlinCompile = K2JVMCompilerIsolate(logger)

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
                // running in test. notion: this may cost 500+ms which will affect compile cost
                ClassGraph().classpathFiles.first { it.name.startsWith("kotlin-android-extensions") }.path
            }
        }

        val packageName = context.packageName
        val kotlinClassPath = module.buildPathInfo.kotlinClassPath
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
            "-verbose",
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-module-name", "${module.name}_${context.variant}",
            "-Xfriend-paths=${kotlinClassPath.absolutePath}",
            "-Xallow-no-source-files",
            "-Xreport-output-files",
            // resolve "class is not abstract and does not implement abstract member"
            "-Xjava-source-roots=${module.sourceDirs.joinToString(",")}",
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
        if (logger.isTraceEnabled) {
            logger.trace("kotlin compile: kotlinc ${command.joinToString(" ")}")
        }

        // resolve kotlin extension function unresolved reference
        val merger = KmModuleMergerForCompilation(kotlinClassPath)
        merger.loadAndMerge()

        val outputParser = KotlinCompilerOutputParser(task.files, logger)
        val exitCode = kotlinCompile.exec(outputParser.printStream, *command.toTypedArray())
        outputParser.flush()
        logger.debug("kotlin compile result code: $exitCode")

        merger.loadAndMerge()
        merger.save()

        if (exitCode != ExitCode.OK) {
            return CompileResult(task, outputParser.results, emptyList())
        }

        // copy outputs to task.outputDir
        val outputs = outputParser.outputs.mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            val targetFile = it.changeBaseDir(kotlinClassPath, task.outputDir)
            targetFile.parentFile?.mkdirs()
            it.copyTo(targetFile, overwrite = true)
            CompileOutput(CompileOutput.Type.Class, targetFile, task.outputDir)
        }

        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }
}
