package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.gradle.compile.crc32
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

        // if compile file is a directory, compile all files in the directory
        val dirToFilesMap: Map<File, List<File>> = createDirToResFileMap(task.files)

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
            val fileName = it.file.flatFileName
            val outputFile = File(outputDir, fileName)
            return@map CompileOutput(CompileOutput.Type.Res, outputFile, File(outputDir))
        }

        val details = task.files.map { compileFile ->
            fun toResult(file: File): Result<CompileFile, CompileError> {
                val fileName = file.flatFileName
                val outputFile = File(outputDir, fileName)
                return if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(compileFile)
                } else {
                    // file not valid, which means compile failed
                    logger.debug("${file.path} compile to flat failed, except name: $fileName")
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
                    val failedFiles = details.filter { !it.isSuccess }.map { it.file.relativeFile.path }
                    return@map Result.failure(CompileError(compileFile, listOf(0L to "res dir compile to flat failed, failed files: $failedFiles")))
                }
            } else {
                return@map Result.failure(CompileError(compileFile, listOf(0L to "compile file not found $compileFile")))
            }
        }

        return CompileResult(task, details, outputs)
    }

    private fun createDirToResFileMap(compileFiles: List<CompileFile>): Map<File, List<File>> {
        return compileFiles
            .filter { it.file.isDirectory }
            .associate { compileFile ->
                val allResFiles = compileFile.file.listFilesRecursively()
                val relativeOldResDirectory = compileFile.oldRes
                val relativeOldFiles = relativeOldResDirectory?.listFilesRecursively()
                if (relativeOldResDirectory == null || relativeOldFiles.isNullOrEmpty()) {
                    logger.debug("${compileFile.dependencyName} has none relative old res files")
                    return@associate compileFile.file to allResFiles
                } else {
                    // filter no changed files
                    logger.debug("${compileFile.dependencyName} has relative old res files: ${compileFile.oldRes}")
                    val checksumMap = relativeOldFiles.associate {
                        it.relativeTo(relativeOldResDirectory).path to it.crc32
                    }
                    val filteredResFiles = allResFiles.filter {
                        val relativePath = it.relativeTo(compileFile.file).path
                        val oldChecksum = checksumMap[relativePath] ?: return@filter true
                        return@filter it.crc32 != oldChecksum
                    }
                    logger.debug("${compileFile.dependencyName} full res files: ${allResFiles.size}, " +
                            "filtered res files: ${filteredResFiles.size}")
                    return@associate compileFile.file to filteredResFiles
                }
            }
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
}