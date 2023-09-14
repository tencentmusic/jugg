package com.sickworm.intellij.jugg.compiler.overlay

import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*

class AssetOverlayCompiler(context: ICompileContext): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Asset, CompileFile.Type.Resource)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
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

            try {
                val outputFile = it.file.copyToBaseDir(it.baseDir, task.outputDir)
                outputs.add(CompileOutput(CompileOutput.Type.Asset, outputFile, task.outputDir))
                details.add(Result.success(it))
            } catch (e: Exception) {
                val errorMessage = "copy file ${it.file.absolutePath} to ${task.outputDir} failed, e: $e"
                logger.warn(errorMessage)
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
            }
        }
        return CompileResult(task, details, outputs)
    }
}