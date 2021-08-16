package com.sickworm.intellij.jugg.compiler.overlay

import com.sickworm.intellij.jugg.AidpInternalException
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.*

class ResourceOverlayCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val resourceCompiler = ResourceCompiler(context)

    private val arscCompiler = ArscCompiler(context)

    override fun doCompile(task: CompileTask): CompileResult {
        // TODO resolve, maybe inc link is already supported
        if (task.files.any { it.file.parent.endsWith("values") }) {
            throw AidpInternalException.resValuesNotSupported()
        }

        // compile to .flat
        val resourceTask = CompileTask(
            task.files,
            context.tempCompileDir
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