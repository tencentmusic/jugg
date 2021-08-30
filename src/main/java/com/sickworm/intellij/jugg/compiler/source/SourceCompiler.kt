package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.changeBaseDir
import com.sickworm.intellij.jugg.clearDir
import com.sickworm.intellij.jugg.compiler.*
import java.io.File

class SourceCompiler(context: ICompileContext): BaseCompiler(context) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)

    private val javaCompiler = JavaCompiler(context)

    private val kotlinCompiler = KotlinCompiler(context)

    private val dexCompiler = DexCompiler(context)

    override fun doCompile(task: CompileTask): CompileResult {
        context.tempCompileDir.clearDir()
        var compileResult = CompileResult(task.copy(outputDir = context.tempCompileDir), emptyList(), emptyList())

        val javaCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Java },
            outputDir = File(context.tempCompileDir, "java")
        )
        if (javaCompileTask.isNeedCompile) {
            compileResult += javaCompiler.compile(javaCompileTask)
        }

        val kotlinCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Kotlin },
            outputDir = File(context.tempCompileDir, "kotlin")
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
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir)
        }
        val dexOutputDir = File(context.tempCompileDir, "dex")
        val dexTask = CompileTask(compileClassFiles, dexOutputDir)
        val dexResult = dexCompiler.compile(dexTask)
        if (!dexResult.isAllSuccess) {
            // TODO handle successfully compiled files
            return compileResult.copy(task = task, outputs = emptyList())
        }

        // move dex files to output dir
        val finalOutputs = dexResult.outputs.map {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
            outputFile.parentFile.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            it.file.renameTo(outputFile)
            CompileOutput(CompileOutput.Type.Dex, outputFile, task.outputDir)
        }


        // move compiled files to class path for future compile dependencies
        val isMoveToClassPathSuccess = classFiles.map {
            val classPathFile = it.file.changeBaseDir(it.baseDir, context.classPathDir)
            classPathFile.parentFile?.mkdirs()
            classPathFile.delete()
            return@map it.file.renameTo(classPathFile)
        }.all { true }
        if (!isMoveToClassPathSuccess) {
            logger.error("move class file to class path failed!")
            // we don't know .class file is from which source file, so all error
            return CompileResult(task, compileResult.details.map { result ->
                Result.failure(CompileError(result.file, emptyList()))
            }, emptyList())
        }

        return CompileResult(task, compileResult.details, finalOutputs)
    }
}
