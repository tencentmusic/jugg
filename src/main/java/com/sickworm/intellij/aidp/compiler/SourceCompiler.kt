package com.sickworm.intellij.aidp.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.*
import java.io.File
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider


class JavaCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Java)

    private val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)

        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
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
                CompileOutput(it, task.outputDir, CompileOutput.Type.Class)
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

class KotlinCompiler: ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (!task.outputDir.listFiles().isNullOrEmpty()) {
            throw AidpInternalException.compileOutputDirNotEmpty()
        }

        val javaCmd = "D:\\Java\\jdk1.8.0_77\\bin\\java.exe"
        val preloader = "D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-preloader.jar org.jetbrains.kotlin.preloading.Preloader"
        val compiler = "D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        val command = "$javaCmd -Xmx256M -Xms32M -noverify -cp $preloader -cp $compiler ${task.files[0].file.absolutePath} -d ${task.outputDir}"
        println(command)
        val pr = Runtime.getRuntime().exec(command)
        pr.waitFor()

        val outputs = task.outputDir.listFilesRecursively().map {
            CompileOutput(it, task.outputDir, CompileOutput.Type.Dex)
        }
        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}

class DexCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Class)

    private val dexFileMaker = DexFileMaker()

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
                outputs.add(CompileOutput(dexOutputFile, task.outputDir, CompileOutput.Type.Dex))
            }
        }
        return CompileResult(task, details, outputs)
    }
}
