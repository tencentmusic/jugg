package com.sickworm.intellij.aidp.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.*
import java.io.File
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

class SourceCompiler(
    /** compile temporary directory */
    private val sourceCompileDir: File,
    /** class path directory */
    private val classPathDir: File,
    androidBuildTools: File,
    private val logger: Logger
    ): ICompiler {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)

    private val javaCompiler = JavaCompiler(logger)

    private val kotlinCompiler = KotlinCompiler(logger)

    private val dexCompiler = DexCompiler(androidBuildTools, logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        sourceCompileDir.clearDir()
        var compileResult = CompileResult(task.copy(outputDir = sourceCompileDir), emptyList(), emptyList())

        val javaCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Java },
            outputDir = File(sourceCompileDir, "java")
        )
        if (javaCompileTask.isNeedCompile) {
            compileResult += javaCompiler.compile(javaCompileTask)
        }

        val kotlinCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Kotlin },
            outputDir = File(sourceCompileDir, "kotlin")
        )
        if (kotlinCompileTask.isNeedCompile) {
            compileResult += kotlinCompiler.compile(kotlinCompileTask)
        }

        if (!compileResult.isAllSuccess) {
            // TODO handle successfully compiled files
            return CompileResult(task, compileResult.details, emptyList())
        }

        // dex .class
        val classFiles = compileResult.outputs.filter {
            it.type == CompileOutput.Type.Class
        }
        val compileClassFiles = classFiles.map {
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir, emptyList())
        }
        val dexTask = CompileTask(compileClassFiles, task.outputDir)
        val dexResult = dexCompiler.compile(dexTask)
        if (!dexResult.isAllSuccess) {
            // TODO handle successfully compiled files
            return compileResult.copy(outputs = emptyList())
        }

        // move compiled files to class path for future compile dependencies
        val isMoveToClassPathSuccess = classFiles.map {
            val classPathFile = it.file.changeBaseDir(it.baseDir, classPathDir)
            classPathFile.parentFile?.mkdirs()
            classPathFile.delete()
            return@map it.file.renameTo(classPathFile)
        }.all { true }
        if (!isMoveToClassPathSuccess) {
            logger.warn("move class file to class path failed!")
            // we don't know .class file is from which source file, so all error
            return CompileResult(task, compileResult.details.map { result ->
                Result.failure(CompileError(result.file, emptyList()))
            }, emptyList())
        }

        return CompileResult(task, compileResult.details, dexResult.outputs)
    }
}

class JavaCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Java)

    private val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)
        task.outputDir.mkdirs()

        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        logger.warn("??? ${dependencies.joinToString(File.pathSeparator)}")
        options.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        // ensure class file version for later dex
        options.addAll(listOf("-source", "1.8"))
        options.addAll(listOf("-target", "1.8"))

        // compile error listener
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            logger.warn("java compile: $diagnostic")
            if (item == null) {
                // it maybe a compile warning like:
                // warning: [options] bootstrap class path not set in conjunction with source -1.7
                return@DiagnosticListener
            }
            item.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        if (!javaTask.call()) {
            logger.warn("javaTask call failed!")
        }

        // check result
        val failedItems = compileItems.filter { it.isFailed }
        // all failed or all success
        return if (failedItems.isEmpty()) {
            val outputs = task.outputDir.listFilesRecursively().map {
                CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
            }
            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs)
        } else {
            CompileResult(task, compileItems.map { Result.failure(CompileError(it.file, it.errors)) }, emptyList())
        }
    }

    private class JavaCompileItem(
        val file: CompileFile,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    ) {
        val isFailed get() = errors.isNotEmpty()
    }
}

class KotlinCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)

        // TODO read from environment
        val javaCmd = if (isWindows) "D:/Java/jdk1.8.0_77/bin/java.exe" else "java"
        val kotlincLibDir = if (isWindows) "D:/JETBRA~1/INTELL~1.2/plugins/Kotlin/kotlinc/lib"
            else "/Users/wormchen/IdeaProjects/studio-master-dev/prebuilts/tools/common/kotlin-plugin/Kotlin/kotlinc/lib"
        val preloader = "$kotlincLibDir/kotlin-preloader.jar org.jetbrains.kotlin.preloading.Preloader"
        val compiler = "$kotlincLibDir/kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        val dependenciesArg = dependencies.joinToString(File.pathSeparator)
        val command = "$javaCmd -Xmx256M -Xms32M -noverify -cp $preloader -cp $compiler ${task.files[0].file.absolutePath} -cp $dependenciesArg -d ${task.outputDir}"
        println(command)
        val pr = Runtime.getRuntime().exec(command)
        pr.readOutput(logger)
        pr.waitFor()

        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }
        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}

class DexCompiler(
    androidBuildTools: File,
    private val logger: Logger,
    ): ICompiler {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    private val dexFileMaker = DexFileMaker(androidBuildTools)

    override fun compile(task: CompileTask): CompileResult {
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        task.files.forEach {
            val dexOutputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "dex")
            dexFileMaker.dex(it.baseDir, dexOutputFile, it.file)

            if (!dexOutputFile.exists() || dexOutputFile.length() <= 0) {
                val errorMessage = "dex failed! file: ${it.file.absolutePath}"
                logger.warn(errorMessage)
                details.add(Result.failure(CompileError(it, listOf(0L to errorMessage))))
            } else {
                details.add(Result.success(it))
                outputs.add(CompileOutput(CompileOutput.Type.Dex, dexOutputFile, task.outputDir))
            }
        }
        return CompileResult(task, details, outputs)
    }
}
