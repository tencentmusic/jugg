package com.sickworm.intellij.aidp.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.DexFileMaker
import com.sickworm.intellij.aidp.Result
import com.sickworm.intellij.aidp.changeBaseDir
import com.sickworm.intellij.aidp.compiler.*
import java.io.File


class DexCompiler(
    androidBuildTools: File,
    private val logger: Logger,
): ICompiler {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    private val dexFileMaker = DexFileMaker(androidBuildTools)

    override fun compile(task: CompileTask): CompileResult {
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
