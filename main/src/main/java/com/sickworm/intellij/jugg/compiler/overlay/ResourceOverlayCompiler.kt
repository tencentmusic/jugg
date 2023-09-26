package com.sickworm.intellij.jugg.compiler.overlay

import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*

/**
 * Compile res file to deployable files.
 *
 * e.g.
 * input:
 * activity_main.xml
 *
 * output:
 * activity_main.xml (compiled)
 * resources.arsc
 * AndroidManifest.xml
 * R.java
 */
class ResourceOverlayCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val resourceCompiler = ResourceCompiler(context)

    private val arscCompiler = ArscCompiler(context)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {

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
                CompileFile(CompileFile.Type.Flat, it.file, it.baseDir, module)
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

    override fun warmUp() {
        arscCompiler.warmUp()
    }
}