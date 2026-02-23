package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.compiler.obfuscation.DexMinifyCompiler
import com.sickworm.intellij.jugg.compiler.source.apt.JuggAptCompiler
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompiler
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.LinkedHashMap

/**
 * SourceCompiler coordinates Java/Kotlin/DataBinding source compilation per module and hands class outputs to downstream dex/minify stages.
 */
class SourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin, CompileFile.Type.Class)

    private val javaCompiler = JavaCompiler(context.subContext("tmp_java"), this)

    private val kotlinCompiler = KotlinCompiler(context.subContext("tmp_kotlin"), this)

    private val dexCompiler = DexCompiler(context.subContext("tmp_dex"), this)

    private val dexMinify = DexMinifyCompiler(context.subContext("minify"), this)

    private val dataBindingGenMapperCompiler = DataBindingGenMapperCompiler(context.subContext("databinding"), this)

    private val juggAptCompiler = JuggAptCompiler(context.subContext("jugg_apt"), this)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        context.tempCompileDir.clearDir()
        val compileTask = CompileTask(
            files = task.files,
            outputDir = context.tempCompileDir,
            parentTask = task,
        )
        var classCompileResult = CompileResult(compileTask, emptyList(), emptyList())

        val juggAptGeneratedFiles = collectJuggAptGeneratedFiles(compileTask, module)

        // Process DataBinding Mapper generated sources before Java compile.
        // Mapper generation depends on source trigger files and resource-phase outputs.
        val dataBindingMapperResult = SourceDataBindingProcessor(dataBindingGenMapperCompiler, context, logger)
            .processDataBindingMapper(task, module)
        if (!dataBindingMapperResult.isAllSuccess) {
            return dataBindingMapperResult.failedAll(task, "DataBinding Mapper generation failed")
        }
        // Compile DataBinding generated Java files (XXXBindingImpl, BR, DataBinderMapper, etc.)
        val dataBindingJavaFiles = dataBindingMapperResult.outputs
            .filter { it.type == CompileOutput.Type.Java }
            .map { CompileFile(CompileFile.Type.Java, it.file, it.baseDir, module) }
        // Kotlin must go first because in the cross-reference case, Java depends on Kotlin compile output
        // while Kotlin don't (kotlin can use -Xjava-source-roots argument)
        var kotlinAptJavaFiles = emptyList<CompileFile>()
        val kotlinCompileTask = CompileTask(
            files = mergeCompileFiles(
                task.files.filter { it.type == CompileFile.Type.Kotlin },
                juggAptGeneratedFiles.filter { it.type == CompileFile.Type.Kotlin },
            ),
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
            files = mergeCompileFiles(
                task.files.filter { it.type == CompileFile.Type.Java },
                juggAptGeneratedFiles.filter { it.type == CompileFile.Type.Java },
                kotlinAptJavaFiles,
                dataBindingJavaFiles,
            ),
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
        } + task.files.filter {
            it.type == CompileFile.Type.Class
        }

        val dexOutputDir = if (context.isMinified) File(context.tempCompileDir, "un_minify") else task.outputDir
        val dexTask = CompileTask(compileClassFiles, dexOutputDir, task)
        val dexCompileResult = dexCompiler.compile(dexTask)
        if (!dexCompileResult.isAllSuccess) {
            return dexCompileResult.failedAll(task,"Dex compile failed")
        }

        if (context.isMinified) {
            val compileDexFiles = dexCompileResult.outputs.map {
                CompileFile(CompileFile.Type.Dex, it.file, it.baseDir, module)
            }
            val minifyTask = CompileTask(compileDexFiles, task.outputDir, task)
            val minifyResult = dexMinify.compile(minifyTask)
            if (!minifyResult.isAllSuccess) {
                return minifyResult.failedAll(task, "Minify failed")
            }
            return CompileResult(task, classCompileResult.details, minifyResult.outputs + otherOutputs)
        }

        return CompileResult(task, classCompileResult.details, dexCompileResult.outputs + otherOutputs)
    }

    override fun warmUp() {
        kotlinCompiler.warmUp()
    }

    /**
     * Runs custom generated-source processors before language compilation.
     *
     * Fail-open strategy: any exception only logs warning and keeps main compile flow available.
     */
    private fun collectJuggAptGeneratedFiles(compileTask: CompileTask, module: ModuleInfo): List<CompileFile> {
        return try {
            val aptCompileTask = CompileTask(
                files = compileTask.files,
                outputDir = File(context.tempCompileDir, "jugg_apt"),
                parentTask = compileTask,
            )
            val aptCompileResult = juggAptCompiler.compile(aptCompileTask)
            aptCompileResult.outputs
                .mapNotNull { it.toCompileFile(module) }
                .filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
                .distinctBy { "${it.type}:${it.file.absolutePath}" }
        } catch (throwable: Throwable) {
            logger.warn("JuggAptCompiler failed: ${throwable.message}")
            emptyList()
        }
    }

    private fun mergeCompileFiles(vararg fileGroups: List<CompileFile>): List<CompileFile> {
        val mergedByPath = LinkedHashMap<String, CompileFile>()
        fileGroups.asList().flatten().forEach { file ->
            mergedByPath["${file.type}:${file.file.absolutePath}"] = file
        }
        return mergedByPath.values.toList()
    }
}
