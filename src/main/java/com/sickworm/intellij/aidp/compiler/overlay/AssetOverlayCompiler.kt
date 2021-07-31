package com.sickworm.intellij.aidp.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.changeBaseDir
import com.sickworm.intellij.aidp.compiler.*

class AssetOverlayCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Asset, CompileFile.Type.Resource)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

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

            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
            try {
                it.file.copyTo(outputFile, overwrite = true)
                outputs.add(CompileOutput(CompileOutput.Type.Overlay, outputFile, task.outputDir))
                details.add(Result.success(it))
            } catch (e: Exception) {
                val errorMessage = "move file ${it.file.absolutePath} to ${outputFile.absolutePath} failed, e: $e"
                logger.warn(errorMessage)
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
            }
        }
        return CompileResult(task, details, outputs)
    }
}