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
        if (task.files.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        val minApi = run {
            // use min(applicationModule.minSdkVersion) as DEX min API
            val applicationMinApis = context.applicationModules.mapNotNull {
                it.minSdkVersion?.toIntOrNull()
            }
            val applicationMinApi = applicationMinApis.minOrNull()
            val isEnableDesugared = context.isEnableDesugared
            val finalMinApi = when {
                // context shows that project is enabled desugar,
                // but other module's minSdkVersion >= 26 (disable desugar).
                // use 21 to enable desugar
                (isEnableDesugared && applicationMinApi != null && applicationMinApi >= 26) -> 21
                // use other module's minSdkVersion as DEX min API
                (applicationMinApi != null && applicationMinApi > 0) -> applicationMinApi
                isEnableDesugared -> 21 // use 21 to enable desugar
                else -> 31 // use 31 to disable desugar
            }
            logger.debug("get minSdkVersion applicationMinApis=${applicationMinApis}), isEnableDesugared = $isEnableDesugared" +
                    ", use $finalMinApi as DEX min API.")
            finalMinApi
        }

        val classpathDir = File(context.tempCompileDir, "classpath")
        val costTime = measureTimeMillis {
            classpathDir.mkdirs()
            classpathDir.clearDir()
            context.getAllDesugarClasspath(task.files, module, classpathDir)
        }
        logger.debug("getAllDesugarClasspath cost ${costTime}ms")

        return try {
            val classFiles = task.files.filter { it.file.extension == "class" }
            var classResult = CompileResult(task, emptyList(), emptyList())
            if (classFiles.isNotEmpty()) {
                classResult = doDex(task, classFiles, classpathDir, minApi, true, "")
            }

            val jarFiles = task.files.filter { it.file.extension != "class" }
            var jarResults = emptyList<CompileResult>()
            if (jarFiles.isNotEmpty()) {
                jarResults = jarFiles.map {
                    doDex(task, listOf(it), classpathDir, minApi, false, it.jarDexFileName)
                }
            }
            CompileResult(
                task,
                classResult.details + jarResults.flatMap { it.details },
                classResult.outputs + jarResults.flatMap { it.outputs },
            )
        } catch (e: Exception) {
            logger.error(e)
            val details:List<Result<CompileFile, CompileError>> = task.files.map {
                Result.failure(CompileError(it, listOf(-1L to (e.message?: ""))))
            }
            CompileResult(task, details, emptyList())
        }
    }

    private fun doDex(
        task: CompileTask,
        files: List<CompileFile>,
        classpathDir: File,
        minApi: Int,
        isFilePerClass: Boolean,
        outputDexName: String,
    ): CompileResult {
        val tempOutput = File(context.tempCompileDir, "output")
        tempOutput.clearDir()

        dexFileMaker.dex(tempOutput, files.map { it.file }, listOf(classpathDir.absolutePath),
            context.androidJar, minApi, isFilePerClass)
        val dexFiles: List<File> = if (isFilePerClass) {
            tempOutput.listFilesRecursively()
        } else {
            val dexFile = File(tempOutput, "classes.dex")
            val renameDexFile = File(tempOutput, outputDexName)
            if (!dexFile.renameTo(renameDexFile)) {
                throw IllegalStateException("rename dex file failed: ${dexFile.absolutePath} -> ${renameDexFile.absolutePath}")
            }
            listOf(renameDexFile)
        }

        val details: List<Result<CompileFile, CompileError>> = files.map {
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
    }

}
