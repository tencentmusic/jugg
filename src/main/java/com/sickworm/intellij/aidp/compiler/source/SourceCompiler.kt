package com.sickworm.intellij.aidp.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.Result
import com.sickworm.intellij.aidp.changeBaseDir
import com.sickworm.intellij.aidp.clearDir
import com.sickworm.intellij.aidp.compiler.*
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File

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

    private val isSupportKotlin = run {
        return@run try {
            K2JVMCompiler()
            true
        } catch (e: Throwable) {
            logger.warn("kotlin compile not support")
            false
        }
    }

    private val kotlinCompiler = if (isSupportKotlin) KotlinCompiler(logger) else EmptyCompiler()

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
