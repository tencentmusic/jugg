package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import java.io.File

class JuggCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Asset,
        CompileFile.Type.Resource
    )

    private val assetOverlayCompiler = AssetOverlayCompiler(context)

    private val resourceOverlayCompiler = ResourceOverlayCompiler(
        context.subContext("overlays")
    )

    private val sourceCompiler = SourceCompiler(
        context.subContext("classes"))

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
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
        if (resourceCompileTask.isNeedCompile) {
            // compile .arsc and R file
            val finalResult = run {
                // compile to .flat
                val tempOutputDir = File(context.tempCompileDir, "tmp_resource")
                tempOutputDir.clearDir()
                val tempResourceCompileTask = resourceCompileTask.copy(outputDir = tempOutputDir)
                val resourceResult = resourceOverlayCompiler.compile(tempResourceCompileTask)
                if (!resourceResult.isAllSuccess) {
                    return@run resourceResult
                }

                // move overlays to output directory
                val overlays = resourceResult.outputs
                    .filter { it.type == CompileOutput.Type.Overlay }
                    .map {
                        val outputFile = it.file.changeBaseDir(it.baseDir, overlayOutputDir)
                        outputFile.parentFile.mkdirs()
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        it.file.renameTo(outputFile)
                        CompileOutput(CompileOutput.Type.Overlay, outputFile, overlayOutputDir)
                    }

                // compile R.java
                val rJavaFile = resourceResult.outputs.find { it.type == CompileOutput.Type.Java }!!
                val rJavaTask = CompileTask(
                    files = listOf(CompileFile(CompileFile.Type.Java, rJavaFile.file, rJavaFile.baseDir, module)),
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
                }

                // successfully compiled .arsc and R.dex
                return@run CompileResult(
                    resourceCompileTask,
                    details = resourceResult.details,
                    outputs = rJavaResult.outputs + overlays
                )
            }
            compileResult += finalResult
        }

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

        return compileResult
    }

    override fun warmUp() {
        sourceCompiler.warmUp()
    }
}