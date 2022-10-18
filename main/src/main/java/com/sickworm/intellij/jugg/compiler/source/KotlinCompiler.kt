package com.sickworm.intellij.jugg.compiler.source

import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.jugg.compiler.*
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File

class KotlinCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = false

    private val kotlinCompile = K2JVMCompilerIsolate(logger)

    private var hasFoundKotlinAndroidExtensions: Boolean = false
    private var kotlinAndroidExtensionsPath: String? = null

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()

        if (!hasFoundKotlinAndroidExtensions) {
            val classLoader = this::class.java.classLoader
            kotlinAndroidExtensionsPath = if (classLoader is UrlClassLoader) {
                // running in IDE
                classLoader.urls.first { it.file.contains("kotlin-android-extensions") }.file
            } else {
                // running in test. notion: this may cost 500+ms which will affect compile cost
                ClassGraph().classpathFiles.first { it.name.startsWith("kotlin-android-extensions") }.path
            }
            hasFoundKotlinAndroidExtensions = true
        }
        if (kotlinAndroidExtensionsPath == null) {
            logger.warn("kotlinAndroidExtensionsPath not found in classpath")
        }

        val packageName = context.packageName
        val kotlinClassPath = module.buildPathInfo.kotlinClassPath.absoluteFile
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
            "-jvm-target", module.kotlinJvmTarget ?: "1.8",
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
        logCompileCommand(command)

        // resolve kotlin extension function unresolved reference
        val merger = KmModuleMergerForCompilation(kotlinClassPath)
        merger.loadAndMerge()

        val outputParser = KotlinCompilerOutputParser(task.files, logger)
        val exitCode = kotlinCompile.exec(outputParser.printStream, command.toTypedArray())
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
            val targetFile = it.copyToBaseDir(kotlinClassPath, task.outputDir)
            CompileOutput(CompileOutput.Type.Class, targetFile, task.outputDir)
        }

        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }

    private fun logCompileCommand(options: List<String>) {
        val baseDir = context.projectDir

        var lastOption = ""
        val shortOptions = options.map {
            if (lastOption == "-cp") {
                return@map it
                    .split(File.pathSeparator)
                    .joinToString(File.pathSeparator) { cpPath ->
                        File(cpPath).relativeToOrSelf(baseDir).path
                    }
            }
            lastOption = it

            if (!it.startsWith('/')) {
                return@map it
            }
            val file = File(it).relativeToOrSelf(baseDir)
            return@map file.path
        }
        logger.debug("kotlin compile base dir: $baseDir")
        logger.debug("kotlin compile: kotlinc ${shortOptions.joinToString(" ")}")
    }

    override fun warnUp() {
        logger.debug("start KotlinCompiler warn up")
        doModuleCompile(CompileTask(emptyList(), context.tempCompileDir), context.modules.values.first())
        logger.debug("finish KotlinCompiler warn up")
    }
}
