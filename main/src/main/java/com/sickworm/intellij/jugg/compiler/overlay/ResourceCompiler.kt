package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
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
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)

    override fun doCompile(task: CompileTask): CompileResult {
        val filePathNames = mutableSetOf<String>()
        var isNeedSplitModule = false
        task.files.forEach {
            val filePathName = it.file.parentFile!!.name + "_" + it.file.nameWithoutExtension
            if (filePathNames.contains(filePathName)) {
                isNeedSplitModule = true
                return@forEach
            }
            filePathNames.add(filePathName)
        }

        logger.debug("isNeedSplitModule: $isNeedSplitModule")
        return if (isNeedSplitModule) {
            super.doCompile(task)
        } else {
            aapt2Compile(task)
        }
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        return aapt2Compile(task, module.name)
    }

    private fun aapt2Compile(task: CompileTask, moduleName: String = ""): CompileResult {
        val subDir = if (moduleName.isEmpty()) "" else "$moduleName/"
        val outputDir = task.outputDir.absolutePath + "/" + subDir
        File(outputDir).mkdirs()

        val dirToFilesMap = task.files
            .filter { it.file.isDirectory }
            .associate {
                it.file to it.file.listFilesRecursively()
            }

        val resFiles: List<CompileFile> = task.files.flatMap {
            if (it.file.isFile) {
                listOf(it)
            } else if (dirToFilesMap.containsKey(it.file)) {
                dirToFilesMap[it.file]!!.map { file ->
                    CompileFile(CompileFile.Type.Resource, file, it.baseDir, context.tempModule)
                }
            } else {
                emptyList()
            }
        }
        if (resFiles.isEmpty()) {
            return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
        }

        val filesString = resFiles.joinToString(" ") {
            it.file.absolutePath
        }

        // --legacy is required for: multiple substitutions specified in non-positional format; did you mean to add the formatted="false" attribute?.
        val command = "compile --legacy -o $outputDir $filesString"
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

        val outputs = resFiles.map {
            val folderName = it.file.parentFile!!.name
            val extension = if (folderName.startsWith("values")) "arsc"
            else it.file.extension
            val fileName = "${folderName}_${it.file.nameWithoutExtension}.$extension.flat"
            val outputFile = File(outputDir, fileName)
            return@map CompileOutput(CompileOutput.Type.Res, outputFile, File(outputDir))
        }

        val details = task.files.map { compileFile ->
            fun toResult(file: File): Result<CompileFile, CompileError> {
                val folderName = file.parentFile!!.name
                val extension = if (folderName.startsWith("values")) "arsc"
                    else file.extension
                val fileName = "${folderName}_${file.nameWithoutExtension}.$extension.flat"
                val outputFile = File(outputDir, fileName)
                return if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(compileFile)
                } else {
                    Result.failure(CompileError(compileFile, listOf(0L to "res file compile to flat failed")))
                }
            }

            if (compileFile.file.isFile) {
                return@map toResult(compileFile.file)
            } else if (dirToFilesMap.containsKey(compileFile.file)) {
                val details = dirToFilesMap[compileFile.file]!!.map {
                    toResult(it)
                }
                val isSuccess = details.all { it.isSuccess }
                if (isSuccess) {
                    return@map Result.success(compileFile)
                } else {
                    return@map Result.failure(CompileError(compileFile, listOf(0L to "res dir compile to flat failed")))
                }
            } else {
                return@map Result.failure(CompileError(compileFile, listOf(0L to "compile file not found $compileFile")))
            }
        }

        return CompileResult(task, details, outputs)
    }

    override fun dispose() {
        aapt2Invoker.release()
    }
}