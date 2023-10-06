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

    override fun doCompile(task: CompileTask): CompileResult {
        var compileResult = CompileResult(task, emptyList(), emptyList())
        val overlayOutputDir = File(task.outputDir, "overlays")
        val classesOutputDir = File(task.outputDir, "classes")

        // compile asset
        val assetsOutputDir = File(overlayOutputDir, "assets")
        val assetCompileTask = task.copy(
            files = task.files.filter {
                it.type == CompileFile.Type.Asset
            },
            outputDir = assetsOutputDir
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
        val resourceCompileTask = task.copy(
            files = task.files.filter {
                it.type == CompileFile.Type.Resource
            },
            outputDir = task.outputDir
        )
        var rJavaResultOutputs: List<CompileOutput> = emptyList()
        if (resourceCompileTask.isNeedCompile) {
            // compile .arsc and R file
            val finalResult = run {
                // compile to .flat
                val tempOutputDir = File(context.tempCompileDir, "tmp_resource")
                tempOutputDir.clearDir()
                val tempResourceCompileTask = resourceCompileTask.copy(outputDir = tempOutputDir)
                val resourceResult = resourceOverlayCompiler.compile(tempResourceCompileTask)
                if (!resourceResult.isAllSuccess) {
                    // avoid JuggInternalException.combineTaskFailed
                    return@run resourceResult.copy(task = tempResourceCompileTask.copy(outputDir = task.outputDir))
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
                        files = listOf(CompileFile(CompileFile.Type.Java, rJavaFile.file, rJavaFile.baseDir, ModuleInfo.virtualModule)),
                        outputDir = classesOutputDir,
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
                    outputs = rJavaResultOutputs + overlays,
                )
            }
            compileResult += finalResult
        }

        // build R.dex for all compiling module if needed
        val rDexResult = rDexForSubmoduleCompiler.compile(
            CompileTask(
                files = rJavaResultOutputs.map {
                    CompileFile(CompileFile.Type.Dex, it.file, it.baseDir, ModuleInfo.virtualModule)
                } + task.files.filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin },
                outputDir = classesOutputDir,
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
        compileResult += rDexResult.copy(task = task) // remove R.dex from compile files

        // compile source
        val sourceCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
            },
            outputDir = classesOutputDir
        )
        if (sourceCompileTask.isNeedCompile) {
            compileResult += sourceCompiler.compile(sourceCompileTask)
        }

        // compile .class
        val dexCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Class
            },
            outputDir = classesOutputDir
        )
        if (dexCompileTask.isNeedCompile) {
            compileResult += dexCompiler.compile(dexCompileTask)
        }

        return compileResult
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    override fun warmUp() {
        sourceCompiler.warmUp()
        resourceOverlayCompiler.warmUp()
    }
}