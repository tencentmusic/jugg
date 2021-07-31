package com.sickworm.intellij.aidp.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.isWindows
import com.sickworm.intellij.aidp.readOutput
import java.io.File

class ResourceCompiler(
    private val androidBuildTools: File,
    private val logger: Logger
    ): ICompiler {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }

        val outputDir = task.outputDir.absolutePath
        val filesString = task.files.map {
            it.file.absolutePath
        }.joinToString(" ")

        val aapt2Name = if (isWindows) "aapt2.exe" else "aapt2"
        val aapt2Cmd = "$androidBuildTools/$aapt2Name"
        val command = "$aapt2Cmd compile -o $outputDir $filesString"
        println(command)
        val process = Runtime.getRuntime().exec(command)
        process.readOutput(logger)
        process.waitFor()

        val detailsAndOutputs = task.files.map {
            val folderName = it.file.parentFile!!.name
            val extension = if (folderName.startsWith("values")) "arsc"
            else it.file.extension
            val fileName = "${folderName}_${it.file.nameWithoutExtension}.$extension.flat"
            val outputFile = File(task.outputDir, fileName)
            val output = CompileOutput(CompileOutput.Type.Overlay, outputFile, task.outputDir)
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