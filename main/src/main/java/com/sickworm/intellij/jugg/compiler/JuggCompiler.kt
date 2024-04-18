package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.compiler.source.DexCompiler
import com.sickworm.intellij.jugg.compiler.overlay.RDexForSubmoduleCompiler
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import java.io.File

class JuggCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Asset,
        CompileFile.Type.Resource,
        CompileFile.Type.Class,
        CompileFile.Type.AndroidManifest
    )

    private val assetOverlayCompiler = AssetOverlayCompiler(context, this)

    private val resourceOverlayCompiler = ResourceOverlayCompiler(
        context.subContext("overlays"),
        this,
    )

    private val sourceCompiler = SourceCompiler(
        context.subContext("classes"),
        this,
    )

    private val dexCompiler = DexCompiler(
        context.subContext("tmp_dex"),
        this,
    )

    private val rDexForSubmoduleCompiler = RDexForSubmoduleCompiler(
        context.subContext("tmp_rfile"),
        this,
    )

    @Synchronized
    override fun doCompile(task: CompileTask): CompileResult {
        var compileResult = CompileResult(task, emptyList(), emptyList())
        val overlayOutputDir = File(task.outputDir, "overlays")
        val classesOutputDir = File(task.outputDir, "classes")

        // compile asset
        val assetsOutputDir = File(overlayOutputDir, "assets")
        val assetCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Asset
            },
            outputDir = assetsOutputDir,
            parentTask = task,
        )
        if (assetCompileTask.isNeedCompile) {
            // overlay assets
            compileResult += assetOverlayCompiler.compile(assetCompileTask).let { result ->
                // correct base dir as assets/xxx/xxx
                result.copy(outputs = result.outputs.map { output ->
                    output.copy(baseDir = output.baseDir.parentFile)
                })
            }
        }

        // compile resource
        val resourceCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Resource
            },
            outputDir = task.outputDir,
            parentTask = task,
        )
        var rJavaResultOutputs: List<CompileOutput> = emptyList()
        if (resourceCompileTask.isNeedCompile) {
            // compile .arsc and R file
            val finalResult = run {
                // compile to .flat
                val tempOutputDir = File(context.tempCompileDir, "tmp_resource")
                tempOutputDir.clearDir()
                val tempResourceCompileTask = CompileTask(
                    files = resourceCompileTask.files,
                    outputDir = tempOutputDir,
                    parentTask = resourceCompileTask,
                )
                val resourceResult = resourceOverlayCompiler.compile(tempResourceCompileTask)
                if (!resourceResult.isAllSuccess) {
                    // avoid JuggInternalException.combineTaskFailed
                    return@run resourceResult.copy(task = resourceCompileTask)
                }

                // move overlays to output directory
                val overlays = resourceResult.outputs
                    .filter { it.type == CompileOutput.Type.Res }
                    .map {
                        val outputFile = it.file.changeBaseDir(it.baseDir, overlayOutputDir)
                        outputFile.parentFile.mkdirs()
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        it.file.renameTo(outputFile)
                        CompileOutput(CompileOutput.Type.Res, outputFile, overlayOutputDir)
                    }

                // compile R.java, it will only be one file
                val rJavaFile = resourceResult.outputs.find { it.type == CompileOutput.Type.Java }
                if (rJavaFile != null) {
                    val rJavaTask = CompileTask(
                        files = listOf(CompileFile(CompileFile.Type.Java, rJavaFile.file, rJavaFile.baseDir, context.tempModule)),
                        outputDir = classesOutputDir,
                        parentTask = task,
                    )
                    val rJavaResult = sourceCompiler.compile(rJavaTask)
                    if (!rJavaResult.isAllSuccess) {
                        return@run CompileResult(
                            resourceCompileTask,
                            resourceCompileTask.files.map {
                                Result.failure(CompileError(it, listOf(0L to "compile R.java failed")))
                            },
                            emptyList()
                        )
                    } else {
                        rJavaResultOutputs = rJavaResult.outputs
                    }
                }

                // successfully compiled .arsc and R.dex
                return@run CompileResult(
                    resourceCompileTask,
                    details = resourceResult.details,
                    outputs = overlays,
                )
            }
            compileResult += finalResult
        }

        // build R.dex for all compiling module if needed
        if (rJavaResultOutputs.isNotEmpty()) {
            val rDexResult = rDexForSubmoduleCompiler.compile(
                CompileTask(
                    files = rJavaResultOutputs.map {
                        CompileFile(CompileFile.Type.DexToChangePackageName, it.file, it.baseDir, context.tempModule)
                    } + task.files.filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin },
                    outputDir = classesOutputDir,
                    parentTask = task,
                )
            )
            if (!rDexResult.isAllSuccess) {
                return CompileResult(
                    task,
                    task.files.map {
                        Result.failure(CompileError(it, listOf(0L to "compile R.dex failed")))
                    },
                    emptyList()
                )
            }
            // we don't need to add R.dex to compile result, because R fields has been inlined
//            compileResult += rDexResult.copy(task = task)
        }

        // compile source
        val sourceCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
            },
            outputDir = classesOutputDir,
            parentTask = task,
        )
        if (sourceCompileTask.isNeedCompile) {
            val sourceCompileResult = sourceCompiler.compile(sourceCompileTask)
            val movedOutputs = sourceCompileResult.outputs.map {
                if (it.type == CompileOutput.Type.Res) {
                    // move from classes output dir to resource output dir
                    val destFile = it.file.changeBaseDir(it.baseDir, overlayOutputDir)
                    destFile.parentFile.mkdirs()
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    it.file.renameTo(destFile)
                    CompileOutput(it.type, destFile, overlayOutputDir)
                } else {
                    it
                }
            }
            compileResult += sourceCompileResult.copy(outputs = movedOutputs)
        }

        // compile .class
        val dexCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Class
            },
            outputDir = classesOutputDir,
            parentTask = task,
        )
        if (dexCompileTask.isNeedCompile) {
            compileResult += dexCompiler.compile(dexCompileTask)
        }

        if (task.isShouldCancel) {
            return task.toCancelResult()
        }
        return compileResult
    }

    @Synchronized
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    @Synchronized
    override fun warmUp() {
        sourceCompiler.warmUp()
        resourceOverlayCompiler.warmUp()
    }
}