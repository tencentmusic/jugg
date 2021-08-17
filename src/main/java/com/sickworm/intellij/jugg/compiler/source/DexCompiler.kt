package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.changeBaseDir
import com.sickworm.intellij.jugg.compiler.*

class DexCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    // TODO jar invoke
    private val dexFileMaker = DexFileMaker()

    override fun doCompile(task: CompileTask): CompileResult {
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        task.files.forEach {
            val dexOutputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "dex")
            dexFileMaker.dex(it.baseDir, dexOutputFile, it.file)

            if (!dexOutputFile.exists() || dexOutputFile.length() <= 0) {
                val errorMessage = "dex failed! file: ${it.file.absolutePath}"
                logger.warn(errorMessage)
                details.add(Result.failure(CompileError(it, listOf(0L to errorMessage))))
            } else {
                details.add(Result.success(it))
                outputs.add(CompileOutput(CompileOutput.Type.Dex, dexOutputFile, task.outputDir))
            }
        }
        return CompileResult(task, details, outputs)
    }
}
