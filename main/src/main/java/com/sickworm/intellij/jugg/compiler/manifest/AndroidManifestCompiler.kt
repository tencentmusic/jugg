package com.sickworm.intellij.jugg.compiler.manifest

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import java.io.File

/**
 * Compile asset file to deployable files.
 * For now just copy the file to output directory.
 *
 * input: AndroidManifest.xml file (xml format)
 * output: merged AndroidManifest.xml file (xml format)
 */
class AndroidManifestCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.AndroidManifest)

    override fun doCompile(task: CompileTask): CompileResult {
        val applicationModule = context.applicationModule
            ?: return createErrorCompileResult(task, "application module not found")
        val finalMergedManifest = applicationModule.buildPathInfo.mergedManifest
        if (!finalMergedManifest.exists()) {
            val reason = "APK merged manifest(${finalMergedManifest}) not exists, fallback to gradle once may fix this."
            return createErrorCompileResult(task, reason)
        }

        val outputManifestFile = File(task.outputDir, "AndroidManifest.xml")
        outputManifestFile.mkdirs()
        outputManifestFile.delete()

        try {
            val changedManifestFileList = task.files.map {
                val module = it.module
                val relativeManifestFile = if (module == context.tempModule) {
                    // AndroidManifest in libraries
                    TODO()
                } else {
                    // AndroidManifest in gradle module
                    findMergedManifestFile(module)
                }

                ChangedManifestFile(it.file, relativeManifestFile)
            }

            AndroidManifestMerger(logger).merge(finalMergedManifest, changedManifestFileList, outputManifestFile)
        } catch (e: Throwable) {
            logger.debug("Compile AndroidManifest.xml failed", e)
            val reason = "Compile AndroidManifest.xml failed, got exception: $e"
            logger.warn(reason)
            return createErrorCompileResult(task, reason)
        }

        if (!outputManifestFile.exists()) {
            val reason = "Compile AndroidManifest.xml failed, file generate failed."
            return createErrorCompileResult(task, reason)
        }

        val compileOutput = CompileOutput(
            CompileOutput.Type.Res,
            outputManifestFile,
            outputManifestFile.parentFile,
        )
        return CompileResult(task, task.files.map { Result.success(it) }, listOf(compileOutput))
    }

    private fun createErrorCompileResult(task: CompileTask, reason: String): CompileResult {
        logger.warn("Compile AndroidManifest.xml failed. $reason.")
        val details: List<Result<CompileFile, CompileError>> = task.files.map {
            Result.failure(CompileError(it, listOf(-1L to reason)))
        }
        return CompileResult(task, details, emptyList())
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    private fun findMergedManifestFile(module: ModuleInfo): File? {
        val manifestFile = module.buildPathInfo.mergedManifest
        if (manifestFile.exists()) {
            return manifestFile
        }

        logger.warn("${manifestFile.absolutePath} not found, compile result may not correct.")
        logger.warn("Fallback to gradle once will fix this.")
        return null
    }
}