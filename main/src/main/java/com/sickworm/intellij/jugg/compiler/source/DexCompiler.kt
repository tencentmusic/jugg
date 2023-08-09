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

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val dependencies = context.getModuleDependencies(module, task)

        val files = task.files.map { it.file }

        try {
            dexFileMaker.dex(task.outputDir, files, dependencies, context.androidJar, context.minApi)
            val dexFiles = task.outputDir.listFilesRecursively()
            val details: List<Result<CompileFile, CompileError>> = task.files.map {
                Result.success(it)
            }
            val outputs: List<CompileOutput> = dexFiles.map {
                CompileOutput(CompileOutput.Type.Dex, it, task.outputDir)
            }
            return CompileResult(task, details, outputs)
        } catch (e: Exception) {
            logger.error(e)
            val details:List<Result<CompileFile, CompileError>> = task.files.map {
                Result.failure(CompileError(it, listOf(-1L to (e.message?: ""))))
            }
            return CompileResult(task, details, emptyList())
        }
    }
}
