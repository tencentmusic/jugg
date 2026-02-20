package com.sickworm.intellij.jugg.compiler.databinding

import android.databinding.tool.BaseDataBinder
import android.databinding.tool.DataBindingBuilder
import android.databinding.tool.LayoutXmlProcessor
import android.databinding.tool.store.LayoutInfoInput
import android.databinding.tool.util.RelativizableFile
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.logger.TimeLogger
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

    init {
        DataBindingArgsManager.isKaAptRetryAptSuccess = false
    }

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
            val splitFiles = splitLayoutXml(argsManager, task.files)
            generateBaseClasses(argsManager, splitFiles)
            // copy will cause gradle compile failed if new file is deleted
            // copy and use full data_binding_layout_info_type_merge will let incremental compile not correct
            copyToGradleDir(argsManager)
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

    private fun splitLayoutXml(argsManager: DataBindingArgsManager, changedXmlFiles: List<CompileFile>): List<File> {
        TimeLogger.start("splitLayoutXml")

        val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingSourcesOutputDir.path)

        val mergingFileLookupInstance = MergingFileLookup(argsManager.blameLogDir)
        val layoutXmlProcessor = LayoutXmlProcessor(argsManager.packageName, gradleFileWriter, mergingFileLookupInstance, argsManager.isUseAndroidX)

        changedXmlFiles.forEach {
            val relativizableFile = RelativizableFile.fromAbsoluteFile(it.file, argsManager.dataBindingStrippedXmlDir)
            val out = File(argsManager.dataBindingStrippedXmlDir, it.relativeFile.path)
            layoutXmlProcessor.processSingleFile(relativizableFile, out, argsManager.isUseViewBinding, argsManager.isUseDataBinding)
            layoutXmlProcessor.writeLayoutInfoFiles(argsManager.tempDataBindingLayoutXmlDir, gradleFileWriter)
        }
        val splitFiles = argsManager.tempDataBindingLayoutXmlDir.listFiles()?.toList()
            ?: throw IllegalStateException("Layout info files not generated in ${argsManager.tempDataBindingLayoutXmlDir}")

        TimeLogger.end("splitLayoutXml", logger)
        return splitFiles
    }

    private fun generateBaseClasses(argsManager: DataBindingArgsManager, splitFiles: List<File>) {
        TimeLogger.start("generateBaseClasses")

        val args = LayoutInfoInput.Args(
            outOfDate = splitFiles,
            removed = emptyList(),
            infoFolder = argsManager.tempDataBindingLayoutXmlDir,
            dependencyClassesFolders = argsManager.dependencyClassesFolders,
            artifactFolder = argsManager.artifactFolder,
            logFolder = argsManager.logFolder,
            packageName = argsManager.packageName,
            incremental = argsManager.isIncremental,
            v1ArtifactsFolder = argsManager.v1ArtifactsFolder,
            useAndroidX = argsManager.isUseAndroidX,
            enableViewBinding = argsManager.isUseViewBinding,
            enableDataBinding = argsManager.isUseDataBinding,
        )
        logger.debug("ViewBinding args: $args")
        val layoutInfoInput = LayoutInfoInput(args)
        val baseDataBinder = BaseDataBinder(layoutInfoInput, getRPackage = null)

        val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingSourcesOutputDir.path)
        baseDataBinder.generateAll(gradleFileWriter)

        TimeLogger.end("generateBaseClasses", logger)
    }

    private fun copyToGradleDir(argsManager: DataBindingArgsManager) {
        argsManager.tempDataBindingLayoutXmlDir.listFiles()?.forEach {
            val targetFile = File(argsManager.backupDataBindingLayoutXmlDir, it.name)
            if (targetFile != it) {
                it.copyTo(targetFile, overwrite = true)
            }
        }
    }

    private fun getOutput(task: CompileTask, argsManager: DataBindingArgsManager, module: ModuleInfo): CompileResult {
        TimeLogger.start("getOutput")
        val isWillRunDataBinding = DataBindingArgsManager.isUseDataBinding(module,
            xmlFile = task.files.filter { it.type == CompileFile.Type.Resource }.map { it.file })

        val sourceFiles: List<CompileOutput>
        if (isWillRunDataBinding) {
            // Return DataBinding trigger file and split XML files
            generateAnnotationProcessorTrigger(argsManager)
            val triggerFile = if (argsManager.isJava) {
                CompileOutput(CompileOutput.Type.Java, argsManager.dataBindingAptSourceTrigger, argsManager.dataBindingPreProcessorSources, relativeModule = module)
            } else {
                CompileOutput(CompileOutput.Type.Kotlin, argsManager.dataBindingKaptSourceTrigger, argsManager.dataBindingPreProcessorSources, relativeModule = module)
            }
            sourceFiles = listOf(triggerFile)
        } else {
            // Return ViewBinding base classes and split XML files
            sourceFiles = argsManager.dataBindingSourcesOutputDir
                .listFilesRecursively()
                .map {
                    val outputDir = task.outputDir.resolve("java")
                    val outputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, outputDir)
                    outputFile.parentFile.mkdirs()
                    it.copyTo(outputFile, overwrite = true)

                    // storage when only use view binding, or will storage them after data binding
                    if (!argsManager.isUseDataBinding) {
                        // storage for incremental compile
                        // let Kotlin java-source-roots works
                        val generatedOutputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, argsManager.incrementalBaseClassOutDir)
                        it.copyTo(generatedOutputFile, overwrite = true)
                    }

                    CompileOutput(CompileOutput.Type.Java, outputFile, outputDir, relativeModule = module)
                }
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
        // storage for incremental compile
        // let new layout convert to binding instance when include by other layout
        argsManager.artifactFolder.listFiles()?.forEach {
            val outputFile = File(argsManager.incrementalDependencyClassesFolder, it.name)
            it.copyTo(outputFile, overwrite = true)
        }

        TimeLogger.end("getOutput", logger)

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            sourceFiles + xmlFiles,
        )
    }

    companion object {
        /**
         * Create DataBindingInfo.java and DataBindingTrigger.kt
         */
        fun generateAnnotationProcessorTrigger(argsManager: DataBindingArgsManager) {
            if (argsManager.isJava) {
                val triggerFile = argsManager.dataBindingAptSourceTrigger
                triggerFile.parentFile.mkdirs()
                val annotation = if (argsManager.isUseAndroidX) "androidx.databinding.BindingBuildInfo" else "android.databinding.BindingBuildInfo"
                val classString = StringBuilder()
                    .appendLine("package ${argsManager.packageName};")
                    .appendLine("@$annotation")
                    .appendLine("public class DataBindingInfo {}")
                triggerFile.writeText(classString.toString())
                if (!triggerFile.exists()) {
                    throw RuntimeException("trigger file not exist: $triggerFile")
                }
            } else {
                val ktSourceTriggerFile = argsManager.dataBindingKaptSourceTrigger
                ktSourceTriggerFile.parentFile.mkdirs()
                val content = StringBuilder()
                    .appendLine("package ${argsManager.packageName}")
                    .appendLine("class DataBindingIncTrigger {}")
                ktSourceTriggerFile.writeText(content.toString())
                if (!ktSourceTriggerFile.exists()) {
                    throw RuntimeException("ktSourceTriggerFile file not exist: $ktSourceTriggerFile")
                }
            }
        }
    }

}