package com.sickworm.intellij.jugg.compiler.overlay

import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import java.io.File

/**
 * Compile res files to .flat files
 *
 * e.g.
 * input:
 * activity_main.xml
 *
 * output:
 * activity_main.xml.flat
 */
class ResourceCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val outputDir = task.outputDir.absolutePath
        val filesString = task.files.map {
            it.file.absolutePath
        }.joinToString(" ")

        val command = "compile -o $outputDir $filesString"
        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            return CompileResult(
                task,
                task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 compile failed")))
                },
                emptyList()
            )
        }

        val detailsAndOutputs = task.files.map {
            val folderName = it.file.parentFile!!.name
            val extension = if (folderName.startsWith("values")) "arsc"
            else it.file.extension
            val fileName = "${folderName}_${it.file.nameWithoutExtension}.$extension.flat"
            val outputFile = File(task.outputDir, fileName)
            val output = CompileOutput(CompileOutput.Type.Res, outputFile, task.outputDir)
            val detail: Result<CompileFile, CompileError> =
                if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(it)
                } else {
                    Result.failure(CompileError(it, listOf(0L to "compile flat failed")))
                }

            return@map detail to output
        }

        return CompileResult(
            task,
            detailsAndOutputs.map { it.first },
            detailsAndOutputs.filter { it.first.isSuccess }.map { it.second }
        )
    }
}