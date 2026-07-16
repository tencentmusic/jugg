package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.JuggInternalException
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File

/**
 * Compile asset file to deployable files.
 * For now just copy the file to output directory.
 */
class AssetOverlayCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(
        CompileFile.Type.Asset,
        CompileFile.Type.ClasspathResource,
        CompileFile.Type.NativeLib,
    )

    override fun doCompile(task: CompileTask): CompileResult {
        return splitApkAndCompile(task)
    }

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeAsset
    override val afterCompileOrderRange: IntRange = CompileOrder.afterAsset

    override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
        // just copy
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()

        task.files.forEach {
            if (!it.file.exists()) {
                val errorMessage = "${it.file.absolutePath} not exists"
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
                return@forEach
            }

            val outputAtApkRoot = it.type == CompileFile.Type.ClasspathResource
            val outputSubDir = when (it.type) {
                CompileFile.Type.Asset -> "assets"
                CompileFile.Type.ClasspathResource -> ""
                CompileFile.Type.NativeLib -> "lib"
                else -> throw JuggInternalException.unrecognizedType(it.type.toString())
            }
            val outputType = when (it.type) {
                CompileFile.Type.Asset, CompileFile.Type.ClasspathResource -> CompileOutput.Type.Asset
                CompileFile.Type.NativeLib -> CompileOutput.Type.NativeLib
                else -> throw JuggInternalException.unrecognizedType(it.type.toString())
            }

            val outputDir = File(task.outputDir, apkFileUnit.getUniquePath(outputSubDir))
            val outputBaseDir = if (outputAtApkRoot) outputDir else task.outputDir
            try {
                if (it.file.isDirectory) {
                    val dirToFilesMap: Map<File, List<File>> = DirToFileMapHelper.createDirToResFileMap(listOf(it), logger)
                    dirToFilesMap.values.firstOrNull()?.forEach { subFile ->
                        val outputFile = subFile.copyToBaseDir(it.baseDir, outputDir)
                        outputs.add(CompileOutput(outputType, outputFile, outputBaseDir, apkFileUnit.apkFile.path))
                    }
                } else {
                    val outputFile = it.file.copyToBaseDir(it.baseDir, outputDir)
                    outputs.add(CompileOutput(outputType, outputFile, outputBaseDir, apkFileUnit.apkFile.path))
                }
                details.add(Result.success(it))
            } catch (e: Exception) {
                val errorMessage = "copy file ${it.file.absolutePath} to $outputDir failed, e: $e"
                logger.warn(errorMessage)
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
            }
        }
        return CompileResult(task, details, outputs)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }
}
