package com.sickworm.intellij.jugg.compiler.databinding

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.*

/**
 * DataBinding compiler step 2:
 * Generate XXXDataBindingImpl.java in generated/kapt/source
 * 1. annotation process
 * 2. generate Mapper proxy
 * 3. merge BR(incremental)
 *
 * References:
 * - Lightning
 * - https://android.googlesource.com/platform/frameworks/data-binding/+/refs/tags/gradle_3.4.0
 * - https://android.googlesource.com/platform/tools/base/+/refs/tags/gradle_3.4.0/build-system
 *
 */
class DataBindingGenMapperCompiler(context: ICompileContext, parent: Disposable): BaseCompiler(context, parent) {

    private lateinit var argsManager: DataBindingArgsManager

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        argsManager = DataBindingArgsManager(context, module)
        if (!argsManager.isUseDataBinding) {
            logger.debug("skip for module ${module.name} because it's not use data binding")
            return CompileResult(task, emptyList(), emptyList())
        }
        if (argsManager.packageName.isEmpty()) {
            logger.warn("Package name not found in module ${module.name}, skipping databinding process")
            return CompileResult(task, emptyList(), emptyList())
        }

        // DataBindingGenMapperCompiler depends on DataBindingGenBaseClassesCompiler output in argsManager's path
        // so cannot reset argsManager here.
        // better way to share DataBindingGenBaseClassesCompiler output?
//        argsManager.reset()

        try {
            generateAnnotationProcessorTrigger()
            runAnnotationProcessor(task, module)
            generateIncrementalMapperHolder()
            mergeLibraryBr()
            mergeAppBr()
            return getOutput(task, module)
        } catch (e: Exception) {
            logger.debug("DataBindingGenMapperCompiler error ", e)
            logger.warn("Compile DataBinding failed: ${e.message}")
            return CompileResult(
                task,
                task.files.map { Result.failure(CompileError(it, listOf(-1L to e.message.toString()))) },
                emptyList())
        }

    }

    /**
     * Create DataBindingInfo.java and DataBindingTrigger.kt
     */
    private fun generateAnnotationProcessorTrigger() {
        logger.debug("generateAnnotationProcessorTrigger trigger.")

        if (argsManager.isJava) {
            val triggerFile = argsManager.dataBindingKaptProcessorTrigger
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

    private fun createFieldsMapFromBrFile(brFile: File): MutableMap<String, String> {
        val lastFieldsMap = LinkedHashMap<String, String>()
        brFile.forEachLine {
            if (it.trim().startsWith("public static final int")) {
                val content = it.trim().replace("public static final int", "").trim().replace(";", "")
                val splits = content.split(" = ")
                lastFieldsMap[splits[0]] = splits[1]
            }
        }
        return lastFieldsMap
    }

    /**
     * Use the file with the same name in DataBinding_BR/merge as the baseline,
     * append the new constants from the newly generated BR file to the end,
     * and then replace the file under DataBinding_BR/merge.
     */
    private fun mergeLibraryBr() {
        val lastLibraryBrFile = argsManager.gradleLibraryBrFile
        val currentIncrementalLibraryBrFile = argsManager.currentIncrementalLibraryBrFile

        if (!lastLibraryBrFile.exists()) {
            throw RuntimeException("library br file not exist: $lastLibraryBrFile")
        }

        logger.debug("merge lib br.java: lastLibraryBrFile = $lastLibraryBrFile")
        logger.debug("merge lib br.java: currentIncrementalLibraryBrFile = $currentIncrementalLibraryBrFile")

        if (!currentIncrementalLibraryBrFile.exists()) {
            logger.debug("merge lib br.java: skip, because current has no br file.")
            return
        }

        val lastFieldsMap = createFieldsMapFromBrFile(lastLibraryBrFile)
        val currentIncrementalFieldsMap = createFieldsMapFromBrFile(currentIncrementalLibraryBrFile)

        var index = lastFieldsMap.size

        currentIncrementalFieldsMap.forEach { (key, _) ->
            if (!lastFieldsMap.containsKey(key)) {
                lastFieldsMap[key] = index++.toString()
            }
        }

        val newLibraryBrFileContent = StringBuilder()
            .append("package com.android.databinding.library.baseAdapters;\n\n")
            .append("public class BR {\n\n")
            .apply {
                lastFieldsMap.forEach { (key, value) ->
                    append("public static final int $key = $value;\n\n")
                }
            }
            .append("}")

        var targetFile = currentIncrementalLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }

        if (!targetFile.exists()) {
            throw RuntimeException("library br file not exist: $targetFile")
        }

        targetFile = lastLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }
    }

    private fun mergeAppBr() {
        val lastLibraryBrFile = argsManager.gradleAppBrFile
        val currentIncrementalLibraryBrFile = argsManager.currentIncrementalAppBrFile

        if (!lastLibraryBrFile.exists()) {
            throw RuntimeException("library br file not exist: $lastLibraryBrFile")
        }

        logger.debug("merge app br.java: lastLibraryBrFile = $lastLibraryBrFile")
        logger.debug("merge app br.java: currentIncrementalLibraryBrFile = $currentIncrementalLibraryBrFile")

        if (!currentIncrementalLibraryBrFile.exists()) {
            logger.debug("merge app br.java: skip, because current has no br file.")
            return
        }

        val lastFieldsMap = createFieldsMapFromBrFile(lastLibraryBrFile)
        val currentIncrementalFieldsMap = createFieldsMapFromBrFile(currentIncrementalLibraryBrFile)

        var index = lastFieldsMap.size

        currentIncrementalFieldsMap.forEach { (key, _) ->
            if (!lastFieldsMap.containsKey(key)) {
                lastFieldsMap[key] = index++.toString()
            }
        }

        val newLibraryBrFileContent = StringBuilder()
            .append("package ${argsManager.packageName};\n\n")
            .append("public class BR {\n\n")
            .apply {
                lastFieldsMap.forEach { (key, value) ->
                    append("public static final int $key = $value;\n\n")
                }
            }
            .append("}")

        var targetFile = currentIncrementalLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }

        targetFile = lastLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }
    }

    /**
     * Get current incremental mapper, copy to the inc directory and rename it to DataBinderMapper_Inc_N.java.
     * According to the contents of all mapper/inc directories, generate a new DataBinderMapperIncrementalHolder.java
     * to the current source directory.
     * For this incremental build, delete the Mapper in the current source directory and generate a new Mapper proxy class.
     */
    private fun generateIncrementalMapperHolder() {
        // 1. get mapper file created this time
        val currentDataBinderMapperImplFile = File(argsManager.dataBindingSourcesOutputDir, argsManager.dataBindingMapperRelativePath)
        if (!currentDataBinderMapperImplFile.exists()) {
            throw RuntimeException("dataBinderMapper file not exist: $currentDataBinderMapperImplFile, " +
                    "kapt may not proceed. see \"runAnnotationProcessor kapt output\" for more details")
        }
        logger.debug("generateIncrementalMapperHolder currentDataBinderMapperImplFile = $currentDataBinderMapperImplFile")

        // 2. create new inc mapper file by currentDataBinderMapperImplFile
        val index = argsManager.databindingIncCount + 1
        val newName = "DataBinderMapperImpl_Inc_$index"
        logger.debug("generateIncrementalMapperHolder newName = $newName, index=$index")
        val targetIncFile = File(argsManager.mapperDir, "$newName.java")
        if (targetIncFile.exists()) {
            targetIncFile.delete()
        }
        if (!targetIncFile.parentFile.exists()) {
            targetIncFile.parentFile.mkdirs()
        }
        val content = currentDataBinderMapperImplFile.readText().replaceFirst("DataBinderMapperImpl", newName)
        targetIncFile.writer().use {
            it.write(content)
        }
        val targetFileCopyToOut = File(currentDataBinderMapperImplFile.parentFile, targetIncFile.name)
        targetIncFile.copyTo(targetFileCopyToOut)
        currentDataBinderMapperImplFile.delete()

        // 3. create DataBinderMapperIncrementalHolder
        val templates = DataBindingTemplates(argsManager.isUseAndroidX)
        val allIncMapper = (1 .. index).map { "DataBinderMapperImpl_Inc_$it" }
        val incMapperArrays = StringBuilder()
        allIncMapper.forEach {
            incMapperArrays.append("\n                                new ${argsManager.packageName}.${it}(),")
        }
        val holderContent = templates.holderTemplate
            .replace("_package_name_holder_", argsManager.packageName)
            .replace("_inc_mapper_array_holder_", incMapperArrays.toString())

        val allIncMapperHolderJavaFile = File(currentDataBinderMapperImplFile.parentFile, "DataBinderMapper_IncrementalHolder.java")
        if (allIncMapperHolderJavaFile.exists()) {
            allIncMapperHolderJavaFile.delete()
        }
        allIncMapperHolderJavaFile.writer().use {
            it.write(holderContent)
        }
        if (!allIncMapperHolderJavaFile.exists()) {
            throw RuntimeException("error to create DataBinderMapperIncrementalHolder : $allIncMapperHolderJavaFile")
        }

        // 4. create delegate file: mapper/DataBinderMapperImpl.java
        val delegateMapperFile = argsManager.dataBindingMapperDelegateFile
        if (!delegateMapperFile.exists()) {
            val delegateMapperContent = templates.mapperContentTemplate.replace("_package_name_holder_", argsManager.packageName)
            delegateMapperFile.writer().use {
                it.write(delegateMapperContent)
            }
        }

        // 5. copy delegate file to the current source directory
        val targetDelegateMapperFile = File(currentDataBinderMapperImplFile.parentFile, delegateMapperFile.name)
        delegateMapperFile.copyTo(targetDelegateMapperFile)

        if (!targetDelegateMapperFile.exists()) {
            throw RuntimeException("Failed to copy file : $targetDelegateMapperFile")
        }

        val fullMapperFile = argsManager.dataBindingMapperFullFile
        if (!fullMapperFile.exists()) {
            DataBindingTemplates(argsManager.isUseAndroidX).generateFullMapperFile(argsManager.gradleMapperFile, fullMapperFile)
        }
        val targetFullMapperFile = File(currentDataBinderMapperImplFile.parentFile, fullMapperFile.name)
        fullMapperFile.copyTo(targetFullMapperFile)
    }

    /**
     * Run kapt，generate DataBindingImpl.java、BR.java、DataMapping.
     */
    private fun runAnnotationProcessor(task: CompileTask, module: ModuleInfo) {
        logger.debug("runAnnotationProcessor in")

        val source = mutableListOf<CompileFile>()
        if (argsManager.isJava) {
            source.add(CompileFile(CompileFile.Type.Java, argsManager.dataBindingKaptProcessorTrigger, argsManager.dataBindingPreProcessorSources, module))
        } else {
            source.add(CompileFile(CompileFile.Type.Kotlin, argsManager.dataBindingKaptSourceTrigger, argsManager.dataBindingPreProcessorSources, module))
        }

        TimeLogger.start("runAnnotationProcessor_findAllIncludePath")
        val includeLayoutInfoFiles = LayoutIncludeAnalyzer(argsManager, logger).findAllIncludePath(task.files)
        logger.debug("runAnnotationProcessor includeLayoutInfoFiles: $includeLayoutInfoFiles")
        includeLayoutInfoFiles.forEach {
            val targetFile = it.changeBaseDir(it.parentFile, argsManager.tempDataBindingLayoutXmlDir)
            targetFile.parentFile.mkdirs()
            it.copyTo(targetFile, overwrite = true)
        }
        TimeLogger.end("runAnnotationProcessor_findAllIncludePath", logger)

        val apOptions = prepareAnnotationProcessorOptions(module)
        logger.debug("runAnnotationProcessor apOptions: $apOptions")

        // kapt compile
        val kaptTask = CompileTask(
            files = source,
            outputDir = argsManager.dataBindingSourcesOutputDir,
            parentTask = task,
        )
        val subContext = context.subContext(argsManager.dataBindingKaptOutputDir)
        val classpath = DataBindingClasspathHelper.getClasspath(context, module, logger)
        classpath.adapterJson.forEach {
            logger.debug("classpath adapterJson: $it")
            val targetFile = File(argsManager.dataBindingDependencyArtifacts, it.name)
            it.copyTo(targetFile, overwrite = true)
        }
        val options = KotlinCompilerInvoker.Options(
            isEnableKapt = true,
            isCanAutoRetry = false,
            kaptOptions = apOptions,
            kaptDependencies = classpath.kaptDependencies,
            kotlinPlugins = classpath.kotlinPlugins,
            javaSourceDirs = listOf(argsManager.dataBindingSourcesOutputDir), // necessary to avoid compilation error
        )
        val kaptResult = KotlinCompilerInvoker.currentInstance.compile(
            subContext, module, kaptTask, logger, options
        )
        if (!kaptResult.isAllSuccess) {
            throw RuntimeException("Failed to compile annotation process task: $kaptResult")
        }
        if (kaptResult.outputs.isEmpty()) {
            throw RuntimeException("No annotation process task output")
        }
        logger.debug("runAnnotationProcessor kapt output: ${kaptResult.outputs.joinToString(", ") { it.file.name }}")
    }

    /**
     * Prepare annotation processor options.
     */
    private fun prepareAnnotationProcessorOptions(module: ModuleInfo): Map<String, String> {
        val artifactType = when (module.moduleType) {
            ModuleInfo.Type.Application -> "APPLICATION"
            ModuleInfo.Type.DynamicFeature -> "FEATURE"
            else -> "LIBRARY"
        }
        return mapOf(
            "android.databinding.incremental" to "1", // not figure out how to let it work for now
            "android.databinding.minApi" to module.minSdkVersion.toString(),
            "android.databinding.classLogDir" to argsManager.dataBindingArtifactFolder.path,
            "android.databinding.aarOutDir" to argsManager.dataBindingAarOutDir.path,
            "android.databinding.enableDebugLogs" to "1",
            "android.databinding.dependencyArtifactsDir" to argsManager.dataBindingDependencyArtifacts.path,
            "android.databinding.sdkDir" to context.androidHome.path,
            "android.databinding.enableForTests" to "0",
            "android.databinding.enableV2" to "1",
            "android.databinding.modulePackage" to argsManager.packageName,
            "android.databinding.artifactType" to artifactType,
            "android.databinding.isTestVariant" to "0",
            "android.databinding.baseFeatureInfoDir" to argsManager.dataBindingBaseFeatureInfoDir.path,
            "android.databinding.printEncodedErrorLogs" to "1",
            "android.databinding.layoutInfoDir" to argsManager.tempDataBindingLayoutXmlDir.path,
            "useAndroidX" to argsManager.isUseAndroidX.toString(),
        )
    }

    private fun getOutput(task: CompileTask, module: ModuleInfo): CompileResult {
        val javaFiles = argsManager.dataBindingSourcesOutputDir.listFilesRecursively().map {
            val outputDir = task.outputDir.resolve("java")
            val outputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, outputDir)
            outputFile.parentFile.mkdirs()
            if (outputFile.exists()) {
                if (!outputFile.delete()) {
                    throw RuntimeException("Failed to delete temporary file ${outputFile.absolutePath}")
                }
            }
            it.copyTo(outputFile)

            // storage for incremental compile
            // let Kotlin java-source-roots works
            val generatedOutputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, argsManager.incrementalBaseClassOutDir)
            it.copyTo(generatedOutputFile, overwrite = true)

            CompileOutput(CompileOutput.Type.Java, outputFile, outputDir, relativeModule = module)
        }
        val xmlFiles = argsManager.dataBindingStrippedXmlDir
            .listFilesRecursively()
            .map {
                val outputDir = task.outputDir.resolve("res")
                val outputFile = it.changeBaseDir(argsManager.dataBindingStrippedXmlDir, outputDir)
                outputFile.parentFile.mkdirs()
                it.copyTo(outputFile, overwrite = true)
                CompileOutput(CompileOutput.Type.ResXml, outputFile, outputDir, relativeModule = module)
            }
        return CompileResult(task, task.files.map { Result.success(it) }, javaFiles + xmlFiles)
    }
}