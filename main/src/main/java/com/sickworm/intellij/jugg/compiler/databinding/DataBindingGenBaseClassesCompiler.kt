package com.sickworm.intellij.jugg.compiler.databinding

import android.databinding.tool.BaseDataBinder
import android.databinding.tool.DataBindingBuilder
import android.databinding.tool.LayoutXmlProcessor
import android.databinding.tool.store.LayoutInfoInput
import android.databinding.tool.util.RelativizableFile
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * DataBinding compiler step 1
 * 1. Generate XXXDataBinding.java in generated/data_binding_base_class_source_out
 * 2. Split XML(if DataBinding is enabled)
 *
 * reference：
 * Lightning
 * - https://android.googlesource.com/platform/frameworks/data-binding/+/refs/tags/gradle_3.4.0
 * - https://android.googlesource.com/platform/tools/base/+/refs/tags/gradle_3.4.0/build-system
 */
class DataBindingGenBaseClassesCompiler(context: ICompileContext, parent: Disposable): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Resource)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (task.files.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }
        val argsManager = DataBindingArgsManager(context, module)
        if (!argsManager.isUseViewBinding && !argsManager.isUseDataBinding) {
            logger.debug("skip for module ${module.name} because it's not use data binding or view binding")
            return CompileResult(task, emptyList(), emptyList())
        }
        if (argsManager.packageName.isEmpty()) {
            logger.warn("Package name not found in module ${module.name}, skipping databinding process")
            return CompileResult(task, emptyList(), emptyList())
        }

        argsManager.reset()
        try {
            splitLayoutXml(argsManager, task.files)
            generateBaseClasses(argsManager)
            return getOutput(task, argsManager, module)
        } catch (e: Exception) {
            logger.debug("DataBindingGenBaseClassesCompiler error ", e)
            logger.warn("Compile DataBinding failed: ${e.message}")
            return CompileResult(
                task,
                task.files.map { Result.failure(CompileError(it, listOf(-1L to e.message.toString()))) },
                emptyList())
        }
    }

    private fun splitLayoutXml(argsManager: DataBindingArgsManager, changedXmlFiles: List<CompileFile>) {
        logger.debug("split layout xml.")


        val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingSourcesOutputDir.path)

        val mergingFileLookupInstance = MergingFileLookup(argsManager.blameLogDir)
        val layoutXmlProcessor = LayoutXmlProcessor(argsManager.packageName, gradleFileWriter, mergingFileLookupInstance, argsManager.isUseAndroidX)

        changedXmlFiles.forEach {
            val relativizableFile = RelativizableFile.fromAbsoluteFile(it.file, argsManager.dataBindingStrippedXmlDir)
            val out = File(argsManager.dataBindingStrippedXmlDir, it.relativeFile.path)
            layoutXmlProcessor.processSingleFile(relativizableFile, out, argsManager.isUseViewBinding, argsManager.isUseDataBinding)
            layoutXmlProcessor.writeLayoutInfoFiles(argsManager.dataBindingLayoutXmlDir, gradleFileWriter)
        }
    }

    private fun generateBaseClasses(argsManager: DataBindingArgsManager) {
        logger.debug("generate base classes.")

        val args = LayoutInfoInput.Args(
            outOfDate = emptyList(),
            removed = emptyList(),
            infoFolder = argsManager.dataBindingLayoutXmlDir,
            dependencyClassesFolders = listOf(argsManager.dependencyClassesFolders),
            artifactFolder = argsManager.artifactFolder,
            logFolder = argsManager.logFolder,
            packageName = argsManager.packageName,
            incremental = argsManager.isIncremental,
            v1ArtifactsFolder = argsManager.v1ArtifactsFolder,
            useAndroidX = argsManager.isUseAndroidX,
            enableViewBinding = argsManager.isUseViewBinding,
            enableDataBinding = argsManager.isUseDataBinding,
        )
        val layoutInfoInput = LayoutInfoInput(args)
        val baseDataBinder = BaseDataBinder(layoutInfoInput, getRPackage = null)

        val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingSourcesOutputDir.path)
        baseDataBinder.generateAll(gradleFileWriter)
    }

    private fun getOutput(task: CompileTask, argsManager: DataBindingArgsManager, module: ModuleInfo): CompileResult {
        val sourceFiles = argsManager.dataBindingSourcesOutputDir
            .listFilesRecursively()
            .map {
                val outputDir = task.outputDir.resolve("java")
                val outputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, outputDir)
                outputFile.parentFile.mkdirs()
                it.copyTo(outputFile, overwrite = true)
                CompileOutput(CompileOutput.Type.Java, outputFile, outputDir, relativeModule = module)
            }
        var xmlFiles = emptyList<CompileOutput>()
        if (argsManager.isUseDataBinding) {
            xmlFiles = argsManager.dataBindingStrippedXmlDir
                .listFilesRecursively()
                .map {
                    val outputDir = task.outputDir.resolve("res")
                    val outputFile = it.changeBaseDir(argsManager.dataBindingStrippedXmlDir, outputDir)
                    outputFile.parentFile.mkdirs()
                    it.copyTo(outputFile, overwrite = true)
                    CompileOutput(CompileOutput.Type.ResXml, outputFile, outputDir, relativeModule = module)
                }
        }

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            sourceFiles + xmlFiles,
        )
    }
}