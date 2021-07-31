package com.sickworm.intellij.aidp.compiler

import com.intellij.openapi.project.Project
import com.sickworm.intellij.aidp.*
import com.sickworm.intellij.aidp.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.aidp.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.aidp.compiler.source.SourceCompiler
import java.io.File

class AidpCompiler(
    project: Project,
    /** compile temporary directory */
    private val tempCompileDir: File,
    /** class path directory */
    classPathDir: File,
    androidJar: File,
    androidBuildTools: File,
    flatDir: File,
    manifest: File,
    stableIds: File,
): ICompiler {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Asset,
        CompileFile.Type.Resource
    )

    private val logger = AidpLogger.getInstance(project, "#AIDP-Compiler")

    private val assetOverlayCompiler = AssetOverlayCompiler(logger)

    private val resourceOverlayCompiler = ResourceOverlayCompiler(
        flatDir = flatDir,
        stableIdsFile = stableIds,
        manifest = manifest,
        androidJar = androidJar,
        androidBuildTools = androidBuildTools,
        logger
    )

    private val sourceCompiler = SourceCompiler(File(tempCompileDir, "classes"), classPathDir, androidBuildTools, logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        logger.info("compile start")
        val startTime = System.currentTimeMillis()

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
                val tempOutputDir = File(tempCompileDir, "resource")
                val tempResourceCompileTask = resourceCompileTask.copy(outputDir = tempOutputDir)
                val resourceResult = resourceOverlayCompiler.compile(tempResourceCompileTask)
                if (!resourceResult.isAllSuccess) {
                    return@run resourceResult
                }

                // move overlays to output directory
                val overlays = resourceResult.outputs
                    .filter { it.type == CompileOutput.Type.Overlay }
                    .map {
                        val outputFile = it.file.changeBaseDir(tempOutputDir, overlayOutputDir)
                        outputFile.parentFile.mkdirs()
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        it.file.renameTo(outputFile)
                        CompileOutput(CompileOutput.Type.Overlay, outputFile, overlayOutputDir)
                    }

                // compile R.java
                val rJavaFile = resourceResult.outputs.find { it.type == CompileOutput.Type.Java }!!.file
                val rJavaOutputDir = File(tempCompileDir, "r")
                val rJavaTask = CompileTask(
                    files = listOf(CompileFile(CompileFile.Type.Java, rJavaFile, rJavaOutputDir)),
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
//                    outputs = overlays.filter { it.file.name != ARSC_FILE_NAME }
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

        val costTime = System.currentTimeMillis() - startTime
        logger.info("compile finished, cost ${costTime}ms")
        logger.info("compile result, success: ${compileResult.successFiles.size}, failure: ${compileResult.failedFiles.size}")

        return compileResult
    }
}