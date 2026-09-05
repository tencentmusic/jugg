package com.sickworm.intellij.jugg.compiler.databinding

import android.databinding.tool.DataBindingBuilder
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.JavaCompilerInvoker
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.util.*

private val DATA_BINDING_ADAPTER_ANNOTATIONS = listOf(
    "BindingAdapter",
    "BindingMethod",
    "BindingMethods",
    "BindingConversion",
    "InverseBindingAdapter",
    "InverseBindingMethod",
    "InverseBindingMethods",
    "InverseMethod",
    "Untaggable",
)

private val dataBindingAdapterAnnotationNames = DATA_BINDING_ADAPTER_ANNOTATIONS.joinToString("|")
private val dataBindingAdapterAnnotationPattern = Regex(
    """@\s*(?:[A-Za-z_][A-Za-z0-9_]*\s*:\s*)?(?:(?:androidx|android)\.databinding\.)?(?:$dataBindingAdapterAnnotationNames)(?=\s|\(|$)"""
)
private val dataBindingAdapterAliasImportPattern = Regex(
    """(?m)^\s*import\s+(?:androidx|android)\.databinding\.(?:$dataBindingAdapterAnnotationNames)\s+as\s+([A-Za-z_][A-Za-z0-9_]*|`[^`\r\n]+`)\s*$"""
)

/** Detects DataBinding annotations without treating comments or longer identifiers as declarations. */
internal fun File.hasDataBindingAdapterDeclaration(): Boolean {
    val source = runCatching { readText().withoutComments() }.getOrDefault("")
    if (dataBindingAdapterAnnotationPattern.containsMatchIn(source)) return true
    return dataBindingAdapterAliasImportPattern.findAll(source).any { match ->
        val alias = Regex.escape(match.groupValues[1])
        Regex("""@\s*(?:[A-Za-z_][A-Za-z0-9_]*\s*:\s*)?$alias(?=\s|\(|$)""")
            .containsMatchIn(source)
    }
}

/** Removes line and block comments while preserving quoted text and line boundaries. */
private fun String.withoutComments(): String {
    val result = StringBuilder(length)
    var index = 0
    var blockCommentDepth = 0
    var isLineComment = false
    var quote = ""
    var isEscaped = false
    while (index < length) {
        if (isLineComment) {
            if (this[index] == '\n') {
                result.append('\n')
                isLineComment = false
            }
            index++
            continue
        }
        if (blockCommentDepth > 0) {
            when {
                startsWith("/*", index) -> blockCommentDepth++
                startsWith("*/", index) -> blockCommentDepth--
                this[index] == '\n' -> result.append('\n')
            }
            index += if (startsWith("/*", index) || startsWith("*/", index)) 2 else 1
            continue
        }
        if (quote.isNotEmpty()) {
            if (quote == "\"\"\"" && startsWith(quote, index)) {
                result.append(quote)
                index += quote.length
                quote = ""
                continue
            }
            val char = this[index++]
            result.append(char)
            if (quote.length == 1) {
                if (isEscaped) isEscaped = false
                else if (char == '\\') isEscaped = true
                else if (char == quote[0]) quote = ""
            }
            continue
        }
        when {
            startsWith("//", index) -> {
                result.append(' ')
                isLineComment = true
                index += 2
            }
            startsWith("/*", index) -> {
                result.append(' ')
                blockCommentDepth = 1
                index += 2
            }
            startsWith("\"\"\"", index) -> {
                quote = "\"\"\""
                result.append(quote)
                index += quote.length
            }
            this[index] == '\"' || this[index] == '\'' -> {
                quote = this[index].toString()
                result.append(this[index++])
            }
            else -> result.append(this[index++])
        }
    }
    return result.toString()
}

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

    private val aptTriggerFile: CompileFile by lazy {
        DataBindingGenBaseClassesCompiler.generateAnnotationProcessorTrigger(argsManager)
        return@lazy CompileFile(CompileFile.Type.Java,
            argsManager.dataBindingAptSourceTrigger,
            argsManager.dataBindingPreProcessorSources,
            context.tempModule)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        argsManager = DataBindingArgsManager(context, module)
        val adapterSources = task.files.filter {
            it.type == CompileFile.Type.Java && it.file.hasDataBindingAdapterDeclaration()
        }
        DataBindingArgsManager.isLastFallbackAptFailed = false

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
            val generatedStore = runAnnotationProcessor(task, module)
            if (adapterSources.isNotEmpty()) {
                val baseline = DataBindingClasspathHelper.getGradleModuleSetterStore(module)
                    ?: error("Gradle DataBinding setter store not found for module ${module.name}")
                val currentStore = generatedStore
                    ?: error("DataBinding processor produced no setter store for module ${module.name}")
                DataBindingSetterStoreCache(argsManager.setterStoreCacheDir).merge(baseline, currentStore)
            }
            if (task.files.none { it.type == CompileFile.Type.Resource }) {
                return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
            }
            generateIncrementalMapperHolder()
            mergeLibraryBr()
            mergeAppBr()
            return getOutput(task, module)
        } catch (e: Exception) {
            logger.debug("Compile DataBinding failed: ${e.message}", e)
            return CompileResult(
                task,
                task.files.map { Result.failure(CompileError(it, listOf(-1L to e.message.toString()))) },
                emptyList())
        }

    }

    /**
     * Generates a setter store for changed Kotlin adapter declarations in an isolated KAPT
     * process. The caller publishes it only after the adapter class compiles successfully.
     */
    internal fun generateKotlinAdapterStore(task: CompileTask, module: ModuleInfo): CompileResult {
        argsManager = DataBindingArgsManager(context, module)
        if (!argsManager.isUseDataBinding) {
            return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
        }
        return try {
            val generatedStore = runKotlinAdapterKapt(task, module)
            CompileResult(
                task,
                task.files.map { Result.success(it) },
                listOf(
                    CompileOutput(
                        CompileOutput.Type.OtherNotDeployed,
                        generatedStore,
                        argsManager.kotlinAdapterKaptAarOutDir,
                    ),
                ),
            )
        } catch (e: Exception) {
            logger.debug("Generate Kotlin DataBinding adapter store failed", e)
            logger.warn("Generate Kotlin DataBinding adapter store failed: ${e.message}")
            CompileResult(
                task,
                task.files.map { Result.failure(CompileError(it, listOf(-1L to e.message.toString()))) },
                emptyList(),
            )
        }
    }

    /** Publishes a generated Kotlin adapter setter store into the module incremental cache. */
    internal fun mergeKotlinAdapterStore(module: ModuleInfo, generatedStore: File) {
        argsManager = DataBindingArgsManager(context, module)
        val baseline = DataBindingClasspathHelper.getGradleModuleSetterStore(module)
            ?: error("Gradle DataBinding setter store not found for module ${module.name}")
        DataBindingSetterStoreCache(argsManager.setterStoreCacheDir).merge(baseline, generatedStore)
    }

    private fun createFieldsMapFromBrFile(brFile: File): MutableMap<String, String> {
        // Keep declaration order stable to generate deterministic BR output.
        val fields = LinkedHashMap<String, String>()

        // Matches both:
        // - public static int user = 1;
        // - public static final int user = 1;
        val brFieldPattern = Regex(
            """^public\s+static(?:\s+final)?\s+int\s+([A-Za-z0-9_]+)\s*=\s*([^;]+);$"""
        )

        brFile.forEachLine { rawLine ->
            val line = rawLine.trim()
            val parsed = parseBrFieldDeclaration(line, brFieldPattern) ?: return@forEachLine
            fields[parsed.name] = parsed.value
        }

        return fields
    }

    private data class BrFieldDeclaration(
        val name: String,
        val value: String,
    )

    /**
     * Parse one BR constant declaration from a single line.
     * Returns null for non-field lines (package/import/class/empty lines, etc.).
     */
    private fun parseBrFieldDeclaration(
        line: String,
        brFieldPattern: Regex,
    ): BrFieldDeclaration? {
        val match = brFieldPattern.matchEntire(line) ?: return null
        return BrFieldDeclaration(
            name = match.groupValues[1].trim(),
            value = match.groupValues[2].trim(),
        )
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
                    "apt may not proceed. see \"runAnnotationProcessor apt output\" for more details")
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
     * Run apt，generate DataBindingImpl.java、BR.java、DataMapping.
     */
    private fun runAnnotationProcessor(task: CompileTask, module: ModuleInfo): File? {
        logger.debug("runAnnotationProcessor in")

        val source = if (argsManager.isJava) {
            task.files.filter { it.type == CompileFile.Type.Java }
        } else {
            task.files.filter { it.type == CompileFile.Type.Kotlin }
        }
        val resource = task.files.filter { it.type == CompileFile.Type.Resource }

        TimeLogger.start("runAnnotationProcessor_findAllIncludePath")
        val includeLayoutInfoFiles = LayoutIncludeAnalyzer(argsManager, logger).findAllIncludePath(resource)
        logger.debug("runAnnotationProcessor includeLayoutInfoFiles: $includeLayoutInfoFiles")
        includeLayoutInfoFiles.forEach {
            val targetFile = it.changeBaseDir(it.parentFile, argsManager.tempDataBindingLayoutXmlDir)
            targetFile.parentFile.mkdirs()
            it.copyTo(targetFile, overwrite = true)
        }
        TimeLogger.end("runAnnotationProcessor_findAllIncludePath", logger)

        prepareBindingClassLog()
        val apOptions = prepareAnnotationProcessorOptions(module)
        logger.debug("runAnnotationProcessor apOptions: $apOptions")

        // apt compile
        val aptTask = CompileTask(
            files = source + (if (argsManager.isFallbackApt) listOf(aptTriggerFile) else emptyList()),
            outputDir = argsManager.dataBindingSourcesOutputDir,
            parentTask = task,
        )
        val subContext = context.subContext(argsManager.dataBindingKaptOutputDir)
        val classpath = DataBindingClasspathHelper.getClasspath(argsManager.isJava, context, module, logger)
        argsManager.dataBindingAarOutDir.clearDir()
        prepareSetterStoreDependencies(classpath.setterStoreFiles)

        val aptResult: CompileResult
        if (argsManager.isJava) {
            // Filter only databinding-related dependencies for annotation processing
            // to avoid issues with other annotation processors like ARouter
            val options = JavaCompilerInvoker.Options(
                isEnableApt = true,
                isAptOnly = true, // Only generate sources, don't compile them
                aptPaths = classpath.aptDependencies,
                isCanAutoRetry = false,
                aptOptions = apOptions,
                aptSourcePaths = listOf(argsManager.dataBindingSourcesOutputDir),
            )
            aptResult = JavaCompilerInvoker().compile(
                subContext, module, aptTask, logger, options
            )
        } else {
            val options = KotlinCompilerInvoker.Options(
                isEnableKapt = true,
                isCanAutoRetry = false,
                kaptOptions = apOptions,
                kaptDependencies = classpath.aptDependencies,
                kotlinPlugins = classpath.kotlinPlugins,
                // Restrict java source roots for databinding kapt to avoid duplicate generated classes
                // from app/build/generated/source/kapt* being compiled again.
                javaSourceDirs = listOf(argsManager.dataBindingSourcesOutputDir),
            )
            aptResult = KotlinCompilerInvoker().compile(
                subContext, module, aptTask, logger, options
            )
            if (!aptResult.isAllSuccess) {
                // allow one retry for kapt
                logger.warn("Kapt failed, retry with apt once")
                argsManager.isJava = true
                val retryAptTask = CompileTask(task.files + aptTriggerFile, task.outputDir, task)
                val generatedStore = try {
                    runAnnotationProcessor(retryAptTask, module)
                } catch (e: Exception) {
                    // Fallback apt also failed; mark so SourceCompiler can retry
                    // after language compilation produces .class files.
                    DataBindingArgsManager.isLastFallbackAptFailed = true
                    throw e
                }
                DataBindingArgsManager.isKaAptRetryAptSuccess = true
                return generatedStore
            }
        }
        if (!aptResult.isAllSuccess) {
            throw RuntimeException("Failed to compile annotation process task: $aptResult")
        }
        val generatedStore = argsManager.dataBindingAarOutDir.listFilesRecursively()
            .filter { it.name.endsWith("-setter_store.json") }
            .sortedBy { it.absolutePath }
            .firstOrNull()
        if (aptResult.outputs.isEmpty() && generatedStore == null) {
            throw RuntimeException("No annotation process task output")
        }
        logger.debug("runAnnotationProcessor apt output: ${aptResult.outputs.joinToString(", ") { it.file.name }}")
        return generatedStore
    }

    private fun prepareBindingClassLog() {
        argsManager.dataBindingArtifactFolder.clearDir()
        argsManager.artifactFolder.listFilesRecursively()
            .filter { it.name.endsWith(DataBindingBuilder.BINDING_CLASS_LIST_SUFFIX) }
            .forEach {
                val targetFile = it.changeBaseDir(argsManager.artifactFolder, argsManager.dataBindingArtifactFolder)
                targetFile.parentFile.mkdirs()
                it.copyTo(targetFile, overwrite = true)
            }
    }

    private fun runKotlinAdapterKapt(task: CompileTask, module: ModuleInfo): File {
        val adapterSources = task.files.filter {
            it.type == CompileFile.Type.Kotlin && it.file.hasDataBindingAdapterDeclaration()
        }
        check(adapterSources.isNotEmpty()) { "No Kotlin DataBinding adapter source found" }
        argsManager.kotlinAdapterKaptAarOutDir.clearDir()
        argsManager.kotlinAdapterKaptLayoutInfoDir.clearDir()

        val classpath = DataBindingClasspathHelper.getClasspath(false, context, module, logger)
        prepareSetterStoreDependencies(classpath.setterStoreFiles)
        val options = KotlinCompilerInvoker.Options(
            isEnableKapt = true,
            isCanAutoRetry = false,
            kaptOptions = prepareAnnotationProcessorOptions(
                module = module,
                aarOutDir = argsManager.kotlinAdapterKaptAarOutDir,
                layoutInfoDir = argsManager.kotlinAdapterKaptLayoutInfoDir,
            ),
            kaptDependencies = classpath.aptDependencies,
            kotlinPlugins = classpath.kotlinPlugins,
            javaSourceDirs = listOf(argsManager.dataBindingSourcesOutputDir),
            executionMode = KotlinCompilerInvoker.ExecutionMode.ISOLATED_PROCESS,
        )
        val kaptTask = CompileTask(
            files = adapterSources,
            outputDir = File(context.tempCompileDir, "databinding_adapter_kapt"),
            parentTask = task,
        )
        val kaptResult = KotlinCompilerInvoker().compile(
            context.subContext(argsManager.kotlinAdapterKaptOutputDir),
            module,
            kaptTask,
            logger,
            options,
        )
        check(kaptResult.isAllSuccess) { "Isolated DataBinding KAPT failed: $kaptResult" }
        return argsManager.kotlinAdapterKaptAarOutDir.listFilesRecursively()
            .filter { it.name.endsWith("-setter_store.json") }
            .sortedBy { it.absolutePath }
            .firstOrNull()
            ?: error("Isolated DataBinding KAPT produced no setter store for module ${module.name}")
    }

    private fun prepareSetterStoreDependencies(setterStoreFiles: List<File>) {
        argsManager.dataBindingDependencyArtifacts.clearDir()
        setterStoreFiles.forEachIndexed { index, setterStoreFile ->
            logger.debug("classpath setterStoreFile: $setterStoreFile")
            val targetFile = File(argsManager.dataBindingDependencyArtifacts, "$index/${setterStoreFile.name}")
            targetFile.parentFile.mkdirs()
            setterStoreFile.copyTo(targetFile, overwrite = true)
        }
    }

    /**
     * Prepare annotation processor options.
     */
    private fun prepareAnnotationProcessorOptions(
        module: ModuleInfo,
        aarOutDir: File = argsManager.dataBindingAarOutDir,
        layoutInfoDir: File = argsManager.tempDataBindingLayoutXmlDir,
    ): Map<String, String> {
        val artifactType = when (module.moduleType) {
            ModuleInfo.Type.Application -> "APPLICATION"
            ModuleInfo.Type.DynamicFeature -> "FEATURE"
            else -> "LIBRARY"
        }
        return mapOf(
            "android.databinding.incremental" to "1", // not figure out how to let it work for now
            "android.databinding.minApi" to module.minSdkVersion.toString(),
            "android.databinding.classLogDir" to argsManager.dataBindingArtifactFolder.path,
            "android.databinding.aarOutDir" to aarOutDir.path,
            "android.databinding.exportClassListOutFile" to argsManager.dataBindingExportClassListOutFile.path,
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
            "android.databinding.layoutInfoDir" to layoutInfoDir.path,
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
