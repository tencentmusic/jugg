package com.sickworm.intellij.jugg.compiler.overlay

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

    override fun doCompile(task: CompileTask): CompileResult {
        // compile to .flat
        val resourceTask = CompileTask(
            task.files,
            context.tempCompileDir
        )
        val resourceResult = resourceCompiler.compile(resourceTask)
        if (!resourceResult.isAllSuccess) {
            return CompileResult(
                task,
                resourceResult.details,
                resourceResult.outputs,
            )
        }

        // build .arsc
        val arscTask = CompileTask(
            resourceResult.outputs.map {
                CompileFile(CompileFile.Type.Flat, it.file, it.baseDir, ModuleInfo.virtualModule)
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

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    override fun warmUp() {
        arscCompiler.warmUp()
    }
}