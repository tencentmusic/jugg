package com.sickworm.intellij.jugg.compiler.overlay

import com.android.tools.idea.gradle.structure.configurables.ui.properties.renderAnyTo
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

        val filesString = task.files.joinToString(" ") {
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

        val detailsAndOutputs = task.files.map {
            val folderName = it.file.parentFile!!.name
            val extension = if (folderName.startsWith("values")) "arsc"
            else it.file.extension
            val fileName = "${folderName}_${it.file.nameWithoutExtension}.$extension.flat"
            val outputFile = File(outputDir, fileName)
            val output = CompileOutput(CompileOutput.Type.Res, outputFile, File(outputDir))
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

    override fun dispose() {
        aapt2Invoker.release()
    }
}