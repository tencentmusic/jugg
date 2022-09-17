package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import java.io.File

class SourceCompiler(context: ICompileContext): BaseCompiler(context) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)

    private val javaCompiler = JavaCompiler(context)

    private val kotlinCompiler = KotlinCompiler(context)

    private val dexCompiler = DexCompiler(context)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
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
        val dependencies = (javaCompileTask.files + kotlinCompileTask.files).flatMap { it.dependencyPaths }
        val compileClassFiles = classFiles.map {
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir, module, dependencyPaths = dependencies)
        }
        val dexOutputDir = File(context.tempCompileDir, "dex")
        val dexTask = CompileTask(compileClassFiles, dexOutputDir)
        val dexResult = dexCompiler.compile(dexTask)
        if (!dexResult.isAllSuccess) {
            return dexResult
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

        return CompileResult(task, compileResult.details, finalOutputs)
    }

    override fun warnUp() {
        kotlinCompiler.warnUp()
    }
}
