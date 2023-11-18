package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import java.io.File

class SourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)

    private val javaCompiler = JavaCompiler(context.subContext("tmp_java"), this)

    private val kotlinCompiler = KotlinCompiler(context.subContext("tmp_kotlin"), this)

    private val dexCompiler = DexCompiler(context.subContext("tmp_dex"), this)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        context.tempCompileDir.clearDir()
        val compileTask = CompileTask(
            files = task.files,
            outputDir = context.tempCompileDir,
            parentTask = task,
        )
        var classCompileResult = CompileResult(compileTask, emptyList(), emptyList())

        // Kotlin must go first because in the cross-reference case, Java depends on Kotlin compile output
        // while Kotlin don't (kotlin can use -Xjava-source-roots argument)
        val kotlinCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Kotlin },
            outputDir = File(context.tempCompileDir, "kotlin"),
            parentTask = compileTask,
        )
        if (kotlinCompileTask.isNeedCompile) {
            classCompileResult += kotlinCompiler.compile(kotlinCompileTask)
        }

        val javaCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Java },
            outputDir = File(context.tempCompileDir, "java"),
            parentTask = compileTask,
        )
        if (javaCompileTask.isNeedCompile) {
            classCompileResult += javaCompiler.compile(javaCompileTask)
        }

        // dex .class
        val classFiles = classCompileResult.outputs.filter {
            it.type == CompileOutput.Type.Class
        }
        val dependencies = (javaCompileTask.files + kotlinCompileTask.files).flatMap { it.dependencyPaths }
        val compileClassFiles = classFiles.map {
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir, module, dependencyPaths = dependencies)
        }
        val dexTask = CompileTask(compileClassFiles, task.outputDir, task)
        val dexCompileResult = dexCompiler.compile(dexTask)
        if (!dexCompileResult.isAllSuccess) {
            // mark all failed
            val successDetails = classCompileResult.details.filter { it.isSuccess }
            val failedDetails = classCompileResult.details.filter { !it.isSuccess }
            val failedDexDetails = successDetails.map {
                Result.failure<CompileFile, CompileError>(CompileError(it.file, listOf(-1L to "Dex compile failed")))
            }
            return CompileResult(task, failedDexDetails + failedDetails, emptyList())
        }
        return CompileResult(task, classCompileResult.details, dexCompileResult.outputs)
    }

    override fun warmUp() {
        kotlinCompiler.warmUp()
    }
}
