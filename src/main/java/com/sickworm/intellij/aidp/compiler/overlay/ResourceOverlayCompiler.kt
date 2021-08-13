package com.sickworm.intellij.aidp.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.AidpInternalException
import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.compiler.*
import java.io.File
import java.util.zip.ZipFile

class ResourceOverlayCompiler(
    apkFile: File,
    androidJar: File,
    private val tempCompileDir: File,
    logger: Logger,
): ICompiler {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val resourceCompiler = ResourceCompiler(logger)

    private val arscCompiler = ArscCompiler(apkFile, androidJar, logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        // TODO resolve, maybe inc link is already supported
        if (task.files.any { it.file.parent.endsWith("values") }) {
            throw AidpInternalException.resValuesNotSupported()
        }

        // compile to .flat
        val resourceTask = CompileTask(
            task.files,
            tempCompileDir
        )
        val resourceResult = resourceCompiler.compile(resourceTask)
        if (!resourceResult.isAllSuccess) {
            return CompileResult(
                task,
                task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 compile failed")))
                },
                emptyList()
            )
        }

        // build .arsc
        val arscTask = CompileTask(
            resourceResult.outputs.map {
                CompileFile(CompileFile.Type.Flat, it.file, it.baseDir)
            },
            task.outputDir
        )
        val arscResult = arscCompiler.compile(arscTask)
        if (!arscResult.isAllSuccess) {
            return CompileResult(
                task,
                task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 link failed")))
                },
                emptyList()
            )
        }

        return CompileResult(
            task,
            resourceResult.details,
            arscResult.outputs
        )
    }
}