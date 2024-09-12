package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.security.MessageDigest

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
            val filePathName = it.file.flatFileName
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
        val outputDir = task.outputDir.resolve(subDir)
        outputDir.mkdirs()

        val singleResCompileSet = ResCompileSet(
            task,
            task.files.filter { it.file.isFile }.associateWith { listOf(it.file) },
            outputDir.resolve("single_files"),
        )
        // if compile file is a directory, compile all files in the directory
        val dirToFilesMap: Map<File, List<File>> = DirToFileMapHelper.createDirToResFileMap(task.files, logger)
        val dirResCompileSet = dirToFilesMap.map { (taskFile, files) ->
            val compileFile = task.files.find { it.file == taskFile }!!
            val outputDirName = "${taskFile.path.md5}_${compileFile.dependencyName}"
            ResCompileSet(
                task,
                mapOf(compileFile to files),
                outputDir.resolve(outputDirName),
            )
        }
        val compileFilesSet: List<ResCompileSet> = dirResCompileSet + listOf(singleResCompileSet)
        val compileResultSet = compileFilesSet.map {
            aapt2Compile(it)
        }
        val compileResult = compileResultSet.reduce { acc, compileResult -> acc + compileResult }.copy(task = task)
        return compileResult
    }

    private fun aapt2Compile(resCompileSet: ResCompileSet): CompileResult {
        if (resCompileSet.compileFiles.isEmpty()) {
            return CompileResult(resCompileSet.originTask, resCompileSet.taskFiles.map { Result.success(it) }, emptyList())
        }

        val filesString = resCompileSet.compileFiles.joinToString(" ") {
            it.absolutePath
        }
        resCompileSet.outputDir.mkdirs()

        // --legacy is required for: multiple substitutions specified in non-positional format; did you mean to add the formatted="false" attribute?.
        val command = "compile --legacy -o ${resCompileSet.outputDir} $filesString"
        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            return CompileResult(
                resCompileSet.originTask,
                resCompileSet.taskFiles.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 compile failed")))
                },
                emptyList()
            )
        }

        val details = resCompileSet.taskFiles.map { compileFile ->

            fun toResult(file: File): Result<CompileFile, CompileError> {
                val fileName = file.flatFileName
                val outputFile = File(resCompileSet.outputDir, fileName)
                return if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(compileFile)
                } else {
                    // file not valid, which means compile failed
                    logger.debug("${file.path} compile to flat failed, except name: $fileName")
                    Result.failure(CompileError(compileFile, listOf(0L to "res file compile to flat failed")))
                }
            }

            val relativeCompileFiles = resCompileSet.compileFileMap[compileFile]!!
            if (relativeCompileFiles.isEmpty()) {
                return@map Result.success(compileFile)
            } else if (relativeCompileFiles.size == 1) {
                return@map toResult(relativeCompileFiles.first())
            } else {
                val details = relativeCompileFiles.map {
                    toResult(it)
                }
                val isSuccess = details.all { it.isSuccess }
                if (isSuccess) {
                    return@map Result.success(compileFile)
                } else {
                    val failedFiles = details.filter { !it.isSuccess }.map { it.file.relativeFile.path }
                    return@map Result.failure(CompileError(compileFile, listOf(0L to "res dir compile to flat failed, failed files: $failedFiles")))
                }
            }
        }

        val outputs = resCompileSet.compileFiles.map {
            val fileName = it.flatFileName
            val outputFile = File(resCompileSet.outputDir, fileName)
            return@map CompileOutput(CompileOutput.Type.Res, outputFile, resCompileSet.outputDir)
        }

        return CompileResult(resCompileSet.originTask, details, outputs)
    }

    private val File.flatFileName: String get() {
        val file = this
        val folderName = file.parentFile!!.name
        val extension = if (folderName.startsWith("values")) ".arsc"
            else if (file.extension.isEmpty()) ""
            else ".${file.extension}"
        return "${folderName}_${file.nameWithoutExtension}$extension.flat"
    }

    override fun dispose() {
        aapt2Invoker.release()
    }

    private class ResCompileSet(
        val originTask: CompileTask,
        val compileFileMap: Map<CompileFile, List<File>>,
        val outputDir: File,
    ) {

        val taskFiles: List<CompileFile> get() = compileFileMap.keys.toList()
        val compileFiles: List<File> get() = compileFileMap.values.flatten()
    }

    private val String.md5: String get() = MessageDigest.getInstance("MD5").digest(this.toByteArray()).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}