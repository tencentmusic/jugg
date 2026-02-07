package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.compiler.source.DexCompiler
import com.sickworm.intellij.jugg.compiler.overlay.RDexForSubmoduleCompiler
import com.sickworm.intellij.jugg.compiler.source.JavaCompilerInvoker
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

class JuggCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Asset,
        CompileFile.Type.NativeLib,
        CompileFile.Type.Resource,
        CompileFile.Type.Class,
        CompileFile.Type.AndroidManifest
    )

    private val assetOverlayCompiler = AssetOverlayCompiler(context, this)

    private val resourceOverlayCompiler = ResourceOverlayCompiler(
        context.subContext("overlays"),
        this,
    )

    private val sourceCompiler = SourceCompiler(
        context.subContext("classes"),
        this,
    )

    private val dexCompiler = DexCompiler(
        context.subContext("tmp_dex"),
        this,
    )

    private val rDexForSubmoduleCompiler = RDexForSubmoduleCompiler(
        context.subContext("tmp_rfile"),
        this,
    )

    override val beforeCompileOrderRange: IntRange = CompileOrder.atFirst
    override val afterCompileOrderRange: IntRange = CompileOrder.atLast

    @Synchronized
    override fun doCompile(task: CompileTask): CompileResult {
        var compileResult = CompileResult(task, emptyList(), emptyList())
        val overlayOutputDir = File(task.outputDir, "overlays")
        val classesOutputDir = File(task.outputDir, "classes")

        // custom compilers
        val compileFiles = task.files
        val customCompilers = context.customCompilers
        logger.debug("custom compilers: ${customCompilers.joinToString { this::class.java.name }}")

        fun checkQuickStop(): CompileResult? {
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }
            if (!compileResult.isAllSuccess) {
                return compileResult.quickFailedOthers(task)
            }
            return null
        }

        // compile asset
        val assetCompileTask = CompileTask(
            files = compileFiles.filter {
                it.type == CompileFile.Type.Asset || it.type == CompileFile.Type.NativeLib
            },
            outputDir = overlayOutputDir,
            parentTask = task,
        )
        if (assetCompileTask.isNeedCompile) {
            // overlay assets
            compileResult += assetOverlayCompiler.compile(assetCompileTask)
            if (!compileResult.isAllSuccess) {
                return compileResult.quickFailedOthers(task)
            }
        }
        checkQuickStop()?.let { return it }

        // compile resource
        val resourceCompileTask = CompileTask(
            files = compileFiles.filter {
                it.type == CompileFile.Type.Resource || it.type == CompileFile.Type.AndroidManifest
            },
            outputDir = task.outputDir,
            parentTask = task,
        )
        var rJavaResultOutputs: List<CompileOutput> = emptyList()
        var dataBindingResultOutputs: List<CompileFile> = emptyList()
        if (resourceCompileTask.isNeedCompile) {
            // compile .arsc and R file
            val finalResult = run {
                // compile to .flat
                val tempOutputDir = File(context.tempCompileDir, "tmp_resource")
                tempOutputDir.clearDir()
                val tempResourceCompileTask = CompileTask(
                    files = resourceCompileTask.files,
                    outputDir = tempOutputDir,
                    parentTask = resourceCompileTask,
                )
                val resourceResult = resourceOverlayCompiler.compile(tempResourceCompileTask)
                if (!resourceResult.isAllSuccess) {
                    // avoid JuggInternalException.combineTaskFailed
                    return@run resourceResult.copy(task = resourceCompileTask)
                }

                // move overlays to output directory
                val overlays = resourceResult.outputs
                    .filter { it.type == CompileOutput.Type.Res }
                    .map {
                        val outputFile = it.file.changeBaseDir(it.baseDir, overlayOutputDir)
                        outputFile.parentFile.mkdirs()
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        it.file.renameTo(outputFile)
                        CompileOutput(CompileOutput.Type.Res, outputFile, overlayOutputDir, it.apkPath)
                    }

                // compile R.java, it will only be one file
                val rJavaFile = resourceResult.outputs.find {
                    it.type == CompileOutput.Type.Java && it.file.name == "R.java"
                }
                if (rJavaFile != null) {
                    val rJavaTask = CompileTask(
                        files = listOf(CompileFile(CompileFile.Type.Java, rJavaFile.file, rJavaFile.baseDir, context.tempModule)),
                        outputDir = classesOutputDir,
                        parentTask = task,
                    )
                    val rJavaResult = sourceCompiler.compile(rJavaTask)
                    if (!rJavaResult.isAllSuccess) {
                        return@run CompileResult(
                            resourceCompileTask,
                            resourceCompileTask.files.map {
                                Result.failure(CompileError(it, listOf(0L to "compile R.java failed")))
                            },
                            emptyList()
                        )
                    } else {
                        rJavaResultOutputs = rJavaResult.outputs
                    }
                }

                // Save ViewBinding base classes or DataBinding Trigger file (generated by ResourceCompiler)
                // Note: DataBinding Mapper (XXXBindingImpl, BR, DataBinderMapper) will be generated in SourceCompiler
                dataBindingResultOutputs = resourceResult.outputs
                    .filter {
                        (it.type == CompileOutput.Type.Java || it.type == CompileOutput.Type.Kotlin) && it.file.name != "R.java"
                    }.map {
                        it.toCompileFile(it.relativeModule!!)!!
                    }

                // successfully compiled .arsc and R.dex
                return@run CompileResult(
                    resourceCompileTask,
                    details = resourceResult.details,
                    outputs = overlays,
                )
            }
            compileResult += finalResult
            if (!compileResult.isAllSuccess) {
                return compileResult.quickFailedOthers(task)
            }
        }

        // build R.dex for all compiling module if needed
        if (rJavaResultOutputs.isNotEmpty()) {
            val rDexResult = rDexForSubmoduleCompiler.compile(
                CompileTask(
                    files = rJavaResultOutputs.map {
                        CompileFile(CompileFile.Type.DexToChangePackageName, it.file, it.baseDir, context.tempModule)
                    } + compileFiles.filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin || it.type == CompileFile.Type.Resource },
                    outputDir = classesOutputDir,
                    parentTask = task,
                )
            )
            if (!rDexResult.isAllSuccess) {
                return CompileResult(
                    task,
                    task.files.map {
                        Result.failure(CompileError(it, listOf(0L to "compile R.dex failed")))
                    },
                    emptyList()
                )
            }
            // we need to add R.dex to compile result, because R.styleable fields won't inlined
            // and R.* won't inline if using kotlinx.android.synthetic
            compileResult += rDexResult.copy(task = task)
        }
        checkQuickStop()?.let { return it }

        // compile source
        val sourceCompileTask = CompileTask(
            files = dataBindingResultOutputs + compileFiles.filter {
                it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin || it.type == CompileFile.Type.Class
            },
            outputDir = classesOutputDir,
            parentTask = task,
        )
        if (sourceCompileTask.isNeedCompile) {
            val sourceCompileResult = sourceCompiler.compile(sourceCompileTask)
            val movedOutputs = sourceCompileResult.outputs.map {
                if (it.type == CompileOutput.Type.Res) {
                    // move from classes output dir to resource output dir
                    val destFile = it.file.changeBaseDir(it.baseDir, overlayOutputDir)
                    destFile.parentFile.mkdirs()
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    it.file.renameTo(destFile)
                    CompileOutput(it.type, destFile, overlayOutputDir, it.apkPath)
                } else {
                    it
                }
            }
            compileResult += sourceCompileResult.copy(
                task = task,
                details = sourceCompileResult.details.filter { it.file !in dataBindingResultOutputs },
                outputs = movedOutputs,
            )
            if (!sourceCompileResult.isAllSuccess) {
                return if (dataBindingResultOutputs.isNotEmpty()) {
                    // mark res files as failed too (since DataBinding depends on resources)
                    compileResult.quickFailedOthers(task, otherFailedFiles = resourceCompileTask.files)
                } else {
                    compileResult.quickFailedOthers(task)
                }
            }
        }
        checkQuickStop()?.let { return it }

        if (!compileResult.isAllSuccess) {
            return compileResult.quickFailedOthers(task)
        }

        checkQuickStop()?.let { return it }
        return compileResult
    }

    @Synchronized
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    @Synchronized
    override fun warmUp() {
        sourceCompiler.warmUp()
        resourceOverlayCompiler.warmUp()
    }

    override fun dispose() {
        logger.debug("dispose")
        KotlinCompilerInvoker.reset()
        JavaCompilerInvoker.reset()
    }
}
