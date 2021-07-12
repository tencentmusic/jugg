package com.sickworm.intellij.aidp.compiler

import com.sickworm.intellij.aidp.AidpInternalException
import com.sickworm.intellij.aidp.Result
import java.io.File

data class CompileTask(
    val files: List<CompileFile>,
    val outputDir: File
) {

    val isNeedCompile get() = files.isNotEmpty()

    operator fun plus(task: CompileTask): CompileTask {
        if (!outputDir.isParentOf(task.outputDir)) {
            throw AidpInternalException.combineTaskFailed()
        }
        return CompileTask(files + task.files.filter { !files.contains(it)}, outputDir)
    }

    private fun File.isParentOf(file: File) = file.path.startsWith(path)

    companion object
}

data class CompileFile(
    val type: Type,
    val file: File,
    val baseDir: File,
    val dependencyPaths: List<String> = emptyList()
) {

    override fun toString(): String {
        return "$type:${file.name}"
    }

    enum class Type {
        Java,
        Kotlin,
        Class,
        Asset,
        Resource,
        FlatDir;
    }
}

data class CompileOutput(
    val type: Type,
    val file: File,
    val baseDir: File,
) {

    enum class Type {
        Class,
        Dex,
        Flat,
        Overlay,
        Java;
    }
}

data class CompileResult(
    val task: CompileTask,
    val details: List<Result<CompileFile, CompileError>>,
    val outputs: List<CompileOutput>
) {
    val successFiles get() = details.filter { it.isSuccess }

    val failedFiles get() = details.filter { it.isFailed }

    val isAllSuccess get() = details.all { it.isSuccess }

    operator fun plus(result: CompileResult): CompileResult {
        return CompileResult(
            task + result.task,
            details + result.details,
            outputs + result.outputs
        )
    }
}

val Result<CompileFile, CompileError>.file: CompileFile
    get() = if (isSuccess) getOrNull()!! else getFailureOrNull()!!.file

data class CompileError(
    /** file to be compiled */
    val file: CompileFile,
    /** will be empty if [file] looks good but compiler still stopped because there is another error file */
    val errors: List<Pair<Long, String>> // <Line, Message>
) {
    val errorMessages get() = errors.joinToString("\n") { it.second }
}

interface ICompiler {
    val supportedTypes: List<CompileFile.Type>

    fun compile(task: CompileTask): CompileResult

    fun checkCanCompile(task: CompileTask) {
        val invalidFiles = task.files.filter { !supportedTypes.contains(it.type) }
        if (invalidFiles.isNotEmpty()) {
            throw AidpInternalException.compilerNotSupported(this, supportedTypes, invalidFiles)
        }
    }

    fun checkOutputDirIsEmpty(task: CompileTask) {
        if (!task.outputDir.listFiles().isNullOrEmpty()) {
            throw AidpInternalException.compileOutputDirNotEmpty()
        }
    }
}
