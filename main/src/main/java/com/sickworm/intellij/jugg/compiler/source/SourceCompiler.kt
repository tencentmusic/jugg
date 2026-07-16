package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.compiler.obfuscation.DexMinifyCompiler
import com.sickworm.intellij.jugg.compiler.source.apt.JuggAptCompiler
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompiler
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.info.ModuleInfo
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

    private data class SourceCompilePreparation(
        val compileTask: CompileTask,
        val juggAptGeneratedFiles: List<CompileFile>,
        val trackedJuggAptChangedFiles: List<ChangedFile>,
        val dataBindingJavaFiles: List<CompileFile>,
    ) {
        companion object {
            fun origin(task: CompileTask) = SourceCompilePreparation(
                task, emptyList(),
                emptyList(), emptyList())
        }
    }

    private data class SourceCompilePreparationResult(
        val isSuccess : Boolean,
        val preparation: SourceCompilePreparation,
        val errorMsg: String = "",
    )

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val prepareResult = prepareSourceCompile(task, module)
        if (!prepareResult.isSuccess) {
            logger.warn("Generate APT/KAPT/KSP failed, the compile result may not be correct. Error: ${prepareResult.errorMsg}")
        }
        val prepared = prepareResult.preparation
        val classCompileResult = compileLanguageStagesWithRetry(
            task = task,
            compileTask = prepared.compileTask,
            module = module,
            juggAptGeneratedFiles = prepared.juggAptGeneratedFiles,
            trackedJuggAptChangedFiles = prepared.trackedJuggAptChangedFiles,
            dataBindingJavaFiles = prepared.dataBindingJavaFiles,
        )
        if (!classCompileResult.isAllSuccess) return classCompileResult.quickFailedOthers(task, isClearOutput = true)

        return compileDexOutputs(task, module, classCompileResult)
    }

    /**
     * Compiles Kotlin then Java with optional JuggApt generated sources.
     * Kotlin is kept first to satisfy Java -> Kotlin compile output dependency.
     */
    private fun compileLanguageStages(
        task: CompileTask,
        compileTask: CompileTask,
        module: ModuleInfo,
        juggAptGeneratedFiles: List<CompileFile>,
        dataBindingJavaFiles: List<CompileFile>,
    ): CompileResult {
        var classCompileResult = CompileResult(compileTask, emptyList(), emptyList())

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
        return classCompileResult
    }

    /**
     * Prepares source compile inputs by collecting JuggApt outputs and DataBinding generated Java files.
     */
    private fun prepareSourceCompile(task: CompileTask, module: ModuleInfo): SourceCompilePreparationResult {
        context.tempCompileDir.clearDir()
        val compileTask = CompileTask(
            files = task.files,
            outputDir = context.tempCompileDir,
            parentTask = task,
        )
        val juggAptGeneratedFiles = collectJuggAptGeneratedFiles(compileTask, module)
        val trackedJuggAptChangedFiles = juggAptGeneratedFiles
            .map { changedFile ->
                ChangedFile(
                    type = changedFile.type,
                    file = changedFile.file,
                    baseDir = changedFile.baseDir,
                    module = changedFile.module,
                    extraInfo = changedFile.extraInfo,
                )
            }
            .distinctBy { it.file.absolutePath }
        if (trackedJuggAptChangedFiles.isNotEmpty()) {
            context.addChangedFile(trackedJuggAptChangedFiles)
        }
        val databindingTask = CompileTask(
            task.files + juggAptGeneratedFiles.filter { it.type == CompileFile.Type.Kotlin },
            task.outputDir, task)
        val dataBindingMapperResult = SourceDataBindingProcessor(dataBindingGenMapperCompiler, kotlinCompiler, context, logger)
            .processDataBindingMapper(databindingTask, module)
        if (!dataBindingMapperResult.isAllSuccess) {
            return SourceCompilePreparationResult(
                isSuccess = false,
                preparation = SourceCompilePreparation.origin(compileTask),
                errorMsg = "DataBinding Mapper generation failed")
        }
        val dataBindingJavaFiles = dataBindingMapperResult.outputs
            .filter { it.type == CompileOutput.Type.Java }
            .map { CompileFile(CompileFile.Type.Java, it.file, it.baseDir, module) }
        return SourceCompilePreparationResult(
            isSuccess = true,
            preparation = SourceCompilePreparation(
                compileTask = compileTask,
                juggAptGeneratedFiles = juggAptGeneratedFiles,
                trackedJuggAptChangedFiles = trackedJuggAptChangedFiles,
                dataBindingJavaFiles = dataBindingJavaFiles,
            ),
        )
    }

    /**
     * Runs language compilation once and retries one more time without JuggApt outputs when retry condition matches.
     */
    private fun compileLanguageStagesWithRetry(
        task: CompileTask,
        compileTask: CompileTask,
        module: ModuleInfo,
        juggAptGeneratedFiles: List<CompileFile>,
        trackedJuggAptChangedFiles: List<ChangedFile>,
        dataBindingJavaFiles: List<CompileFile>,
    ): CompileResult {
        val firstCompileResult = compileLanguageStages(
            task = task,
            compileTask = compileTask,
            module = module,
            juggAptGeneratedFiles = juggAptGeneratedFiles,
            dataBindingJavaFiles = dataBindingJavaFiles,
        )
        if (firstCompileResult.isAllSuccess || !shouldRetryWithoutJuggApt(firstCompileResult, juggAptGeneratedFiles)) {
            return firstCompileResult
        }
        if (trackedJuggAptChangedFiles.isNotEmpty()) {
            context.removeChangedFile(trackedJuggAptChangedFiles.map { it.file })
        }
        logger.warn("JuggApt generated sources caused compile failure, retry once without JuggApt outputs.")
        return compileLanguageStages(
            task = task,
            compileTask = compileTask,
            module = module,
            juggAptGeneratedFiles = emptyList(),
            dataBindingJavaFiles = dataBindingJavaFiles,
        )
    }

    /**
     * Compiles class outputs to dex and applies optional minify stage for minified build variants.
     */
    private fun compileDexOutputs(task: CompileTask, module: ModuleInfo, classCompileResult: CompileResult): CompileResult {
        val otherOutputs = classCompileResult.outputs.filter { it.type != CompileOutput.Type.Class }
        val compileClassFiles = classCompileResult.outputs
            .filter { it.type == CompileOutput.Type.Class }
            .map { CompileFile(CompileFile.Type.Class, it.file, it.baseDir, module) } +
            task.files.filter { it.type == CompileFile.Type.Class }

        val dexOutputDir = if (context.isMinified) File(context.tempCompileDir, "un_minify") else task.outputDir
        val dexTask = CompileTask(compileClassFiles, dexOutputDir, task)
        val dexCompileResult = dexCompiler.compile(dexTask)
        if (!dexCompileResult.isAllSuccess) return dexCompileResult.failedAll(task, "Dex compile failed")

        if (!context.isMinified) {
            return CompileResult(task, classCompileResult.details, dexCompileResult.outputs + otherOutputs)
        }

        val compileDexFiles = dexCompileResult.outputs
            .map { CompileFile(CompileFile.Type.Dex, it.file, it.baseDir, module) }
        val minifyTask = CompileTask(compileDexFiles, task.outputDir, task)
        val minifyResult = dexMinify.compile(minifyTask)
        if (!minifyResult.isAllSuccess) return minifyResult.failedAll(task, "Minify failed")
        return CompileResult(task, classCompileResult.details, minifyResult.outputs + otherOutputs)
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

    /**
     * Retries only when failed files include shadow outputs produced by JuggApt processors.
     */
    private fun shouldRetryWithoutJuggApt(
        compileResult: CompileResult,
        juggAptGeneratedFiles: List<CompileFile>,
    ): Boolean {
        if (compileResult.isAllSuccess || juggAptGeneratedFiles.isEmpty()) {
            return false
        }
        val juggAptFileSet = juggAptGeneratedFiles
            .map { "${it.type}:${it.file.absolutePath}" }
            .toHashSet()
        return compileResult.failedFiles.any { failed ->
            val compileError = failed.getFailure()
            val failedFile = compileError.file
            val isJuggAptFile = "${failedFile.type}:${failedFile.file.absolutePath}" in juggAptFileSet
            isJuggAptFile && compileError.hasDirectSourceDiagnostic
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
