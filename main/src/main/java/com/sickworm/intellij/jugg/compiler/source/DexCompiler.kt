package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import java.io.File
import kotlin.system.measureTimeMillis

class DexCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    override val isNeedPrintProgress: Boolean = true

    private val dexFileMaker = DexFileMaker(logger)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val classpathDir = File(context.tempCompileDir, "classpath")
        val costTime = measureTimeMillis {
            classpathDir.mkdirs()
            classpathDir.clearDir()
            context.getAllDesugarClasspath(task.files, module, classpathDir)
        }
        logger.debug("getAllDesugarClasspath cost $costTime ms")

        val files = task.files.map { it.file }

        try {
            val tempOutput = File(context.tempCompileDir, "output")
            tempOutput.clearDir()
            val minApi = module.minSdkVersion?.toIntOrNull() ?: run {
                // if minSdkVersion is null
                // use min(module.minSdkVersion) as DEX min API
                // use 21 as if all minSdkVersion is null
                val otherMinApis = context.modules.values.mapNotNull {
                    it.minSdkVersion?.toIntOrNull()
                }
                val otherMinApi = otherMinApis.minOrNull()
                val finalMinApi = if (otherMinApi != null && otherMinApi > 0) otherMinApi else 21
                logger.debug("get minSdkVersion failed(minSdkVersion=${module.minSdkVersion}, otherMinApis=${otherMinApis}), use $finalMinApi as DEX min API.")
                finalMinApi
            }
            dexFileMaker.dex(tempOutput, files, listOf(classpathDir.absolutePath), context.androidJar, minApi)
            val dexFiles = tempOutput.listFilesRecursively()
            val details: List<Result<CompileFile, CompileError>> = task.files.map {
                Result.success(it)
            }
            val outputs: List<CompileOutput> = dexFiles.map {
                CompileOutput(CompileOutput.Type.Dex, it, tempOutput)
            }

            val finalOutputs = outputs.map {
                val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
                outputFile.parentFile.mkdirs()
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                it.file.renameTo(outputFile)
                CompileOutput(CompileOutput.Type.Dex, outputFile, task.outputDir)
            }

            return CompileResult(task, details, finalOutputs)
        } catch (e: Exception) {
            logger.error(e)
            val details:List<Result<CompileFile, CompileError>> = task.files.map {
                Result.failure(CompileError(it, listOf(-1L to (e.message?: ""))))
            }
            return CompileResult(task, details, emptyList())
        }
    }
}
