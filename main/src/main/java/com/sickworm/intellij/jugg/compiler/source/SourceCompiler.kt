package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassMinifyCompiler
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompiler
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

class SourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)

    private val javaCompiler = JavaCompiler(context.subContext("tmp_java"), this)

    private val kotlinCompiler = KotlinCompiler(context.subContext("tmp_kotlin"), this)

    private val dexCompiler = DexCompiler(context.subContext("tmp_dex"), this)

    private val classMinify = ClassMinifyCompiler(context.subContext("minify"), this)

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
        var kotlinAptJavaFiles = emptyList<CompileFile>()
        val kotlinCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Kotlin },
            outputDir = File(context.tempCompileDir, "kotlin"),
            parentTask = compileTask,
        )
        if (kotlinCompileTask.isNeedCompile) {
            val kotlinCompileResult = kotlinCompiler.compile(kotlinCompileTask)
            if (!kotlinCompileResult.isAllSuccess) {
                val otherDetails: List<Result<CompileFile, CompileError>> = task.files
                    .filter { it.type != CompileFile.Type.Kotlin }
                    .map {
                        Result.failure(CompileError(it, listOf(-1L to "Kotlin compile failed, skip")))
                    }
                return CompileResult(task, kotlinCompileResult.details + otherDetails, kotlinCompileResult.outputs)
            }

            kotlinAptJavaFiles = kotlinCompileResult.outputs
                .filter { it.type == CompileOutput.Type.Java }
                .map { CompileFile(CompileFile.Type.Java, it.file, it.baseDir, module) }
            classCompileResult += kotlinCompileResult
        }
        if (!classCompileResult.isAllSuccess) {
            return classCompileResult.quickFailedOthers(task, isClearOutput = true)
        }

        val javaCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Java } + kotlinAptJavaFiles,
            outputDir = File(context.tempCompileDir, "java"),
            parentTask = compileTask,
        )
        if (javaCompileTask.isNeedCompile) {
            classCompileResult += javaCompiler.compile(javaCompileTask)
        }
        if (!classCompileResult.isAllSuccess) {
            return classCompileResult.quickFailedOthers(task, isClearOutput = true)
        }

        // e.g. META-INF/service/xxx
        val otherOutputs = classCompileResult.outputs.filter {
            it.type != CompileOutput.Type.Class
        }

        // minify by mapping for minified apk
        val classFiles = classCompileResult.outputs.filter {
            it.type == CompileOutput.Type.Class
        }
        val compileClassFiles = classFiles.map {
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir, module)
        }
        val outputDir = File(context.tempCompileDir, "minify")
        val minifyTask = CompileTask(compileClassFiles, outputDir, task)
        val minifyResult = classMinify.compile(minifyTask)
        if (!minifyResult.isAllSuccess) {
            return classCompileResult.failedAll("Minify failed")
        }

        // dex .class
        val minifyClassFiles = minifyResult.outputs.map {
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir, module)
        }
        val dexTask = CompileTask(minifyClassFiles, task.outputDir, task)
        val dexCompileResult = dexCompiler.compile(dexTask)
        if (!dexCompileResult.isAllSuccess) {
            return classCompileResult.failedAll("Dex compile failed")
        }

        return CompileResult(task, classCompileResult.details, dexCompileResult.outputs + otherOutputs)
    }

    override fun warmUp() {
        kotlinCompiler.warmUp()
    }
}
