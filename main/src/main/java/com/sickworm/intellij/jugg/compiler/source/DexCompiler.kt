package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import java.io.File

class DexCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    override val isNeedPrintProgress: Boolean = true

    private val dexFileMaker = DexFileMaker()

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val dependencies = context.getModuleDependencies(module, task)

        val files = task.files.map { it.file }

        try {
            val tempOutput = context.tempCompileDir
            tempOutput.clearDir()
            dexFileMaker.dex(tempOutput, files, dependencies, context.androidJar, context.minApi)
            val dexFiles = tempOutput.listFilesRecursively()
            val details: List<Result<CompileFile, CompileError>> = task.files.map {
                Result.success(it)
            }
            val outputs: List<CompileOutput> = dexFiles.map {
                CompileOutput(CompileOutput.Type.Dex, it, tempOutput)
            }

            val finalOutputs = outputs.map {
                val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
                outputFile.parentFile.mkdirs()
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                it.file.renameTo(outputFile)
                CompileOutput(CompileOutput.Type.Dex, outputFile, task.outputDir)
            }

            return CompileResult(task, details, finalOutputs)
        } catch (e: Exception) {
            logger.error(e)
            val details:List<Result<CompileFile, CompileError>> = task.files.map {
                Result.failure(CompileError(it, listOf(-1L to (e.message?: ""))))
            }
            return CompileResult(task, details, emptyList())
        }
    }
}
