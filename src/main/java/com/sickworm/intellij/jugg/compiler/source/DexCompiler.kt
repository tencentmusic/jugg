package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively

class DexCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    override val isNeedOutputDirEmpty: Boolean = true

    private val dexFileMaker = DexFileMaker()

    override fun doCompile(task: CompileTask): CompileResult {
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        val files = task.files.map { it.file }
        val isSuccess = dexFileMaker.dex(task.outputDir, files, dependencies)

        val dexFiles = task.outputDir.listFilesRecursively()
        var errorMessage = ""
        if (!isSuccess) {
            // just simple check because I can't determine how many files will create
            // for it may has desuger operation
            errorMessage = "dex failed! expect files size: ${task.files.size}, actual: ${dexFiles.size}"
            logger.warn(errorMessage)
        }

        // all success or all failed
        val details: List<Result<CompileFile, CompileError>>
        val outputs: List<CompileOutput>
        if (errorMessage.isNotEmpty()) {
            details = task.files.map {
                Result.failure(CompileError(it, listOf(-1L to errorMessage)))
            }
            outputs = emptyList()
        } else {
            details = task.files.map {
                 Result.success(it)
            }
            outputs = dexFiles.map {
                CompileOutput(CompileOutput.Type.Dex, it, task.outputDir)
            }
        }

        return CompileResult(task, details, outputs)
    }
}
