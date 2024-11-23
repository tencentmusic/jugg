package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Compile asset file to deployable files.
 * For now just copy the file to output directory.
 */
class AssetOverlayCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Asset, CompileFile.Type.NativeLib)

    override fun doCompile(task: CompileTask): CompileResult {
        // just copy
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()

        task.files.forEach {
            if (!it.file.exists()) {
                val errorMessage = "${it.file.absolutePath} not exists"
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
                return@forEach
            }

            val outputSubDir = when (it.type) {
                CompileFile.Type.Asset -> "assets"
                CompileFile.Type.NativeLib -> "lib"
                else -> throw JuggInternalException.unrecognizedType(it.type.toString())
            }
            val outputType = when (it.type) {
                CompileFile.Type.Asset -> CompileOutput.Type.Asset
                CompileFile.Type.NativeLib -> CompileOutput.Type.NativeLib
                else -> throw JuggInternalException.unrecognizedType(it.type.toString())
            }

            val outputDir = File(task.outputDir, outputSubDir)
            try {
                if (it.file.isDirectory) {
                    val dirToFilesMap: Map<File, List<File>> = DirToFileMapHelper.createDirToResFileMap(listOf(it), logger)
                    dirToFilesMap.values.firstOrNull()?.forEach { subFile ->
                        val outputFile = subFile.copyToBaseDir(it.baseDir, outputDir)
                        outputs.add(CompileOutput(outputType, outputFile, task.outputDir))
                    }
                } else {
                    val outputFile = it.file.copyToBaseDir(it.baseDir, outputDir)
                    outputs.add(CompileOutput(outputType, outputFile, task.outputDir))
                }
                details.add(Result.success(it))
            } catch (e: Exception) {
                val errorMessage = "copy file ${it.file.absolutePath} to $outputDir failed, e: $e"
                logger.warn(errorMessage)
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
            }
        }
        return CompileResult(task, details, outputs)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }
}