package com.sickworm.intellij.jugg.compiler.compose

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.BaseCompiler
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.compiler.toCancelResult
import com.sickworm.intellij.jugg.project.data.ComposeResourceDirectory
import com.sickworm.intellij.jugg.project.data.ComposeResourceInfo
import com.sickworm.intellij.jugg.project.data.ComposeResourceSupportStatus
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Prepares Compose resources and compiles their generated Kotlin without Gradle. */
class ComposeResourceCompiler(
    context: ICompileContext,
    parent: Disposable,
) : BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.ComposeResource)

    private val converter = ComposeValueResourceConverter()
    private val scanner = ComposeResourceScanner()
    private val generator = ComposeResourceGeneratorBridge(this)
    private val kotlinCompiler = KotlinCompilerInvoker()

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val owner = resolveInvocationOwner(module)
        val info = owner.composeResourceInfo
            ?: return task.allFailed("Compose resource configuration is missing for ${owner.name}")
        if (info.supportStatus == ComposeResourceSupportStatus.Unsupported) {
            val reason = info.unsupportedReason ?: "Compose resource incremental compilation is unsupported."
            logger.warn(reason)
            return task.allFailed(reason)
        }
        val workDir = File(context.tempCompileDir, moduleWorkDirName(owner))
        workDir.clearDir()
        return try {
            val prepared = prepareResources(info, workDir)
            if (task.isShouldCancel) return cancel(task, workDir)
            val generated = generateSources(owner, info, prepared, workDir)
            if (task.isShouldCancel) return cancel(task, workDir)
            val classResult = compileGenerated(task, owner, generated, workDir)
            if (task.isShouldCancel) return cancel(task, workDir)
            if (!classResult.isAllSuccess) return mapGeneratedCompileFailure(task, classResult)
            successResult(task, owner, prepared, classResult.outputs)
        } catch (exception: Exception) {
            logger.warn("Compose resource compilation failed: ${exception.message}")
            logger.debug("Compose resource compilation detail", exception)
            task.allFailed(exception.message ?: "Compose resource compilation failed")
        }
    }

    private fun resolveInvocationOwner(module: ModuleInfo): ModuleInfo {
        if (module.composeResourceInfo != null) return module
        val owner = context.modules.values.singleOrNull {
            it.moduleRootDir == module.moduleRootDir &&
                it.moduleType.isAndroidModule &&
                it.composeResourceInfo != null
        } ?: return module
        logger.debug("Use Android owner ${owner.name} for Compose resource module ${module.name}")
        return owner
    }

    private fun prepareResources(info: ComposeResourceInfo, workDir: File): PreparedResources {
        if (info.usesLegacyGenerator) {
            return PreparedResources(
                resources = info.resourceDirectories.associate { directory ->
                    directory.sourceSetName to scanner.scanLegacy(directory.directory)
                },
                preparedValues = emptyMap(),
                assetRoot = File(workDir, "assets"),
            )
        }
        val resources = linkedMapOf<String, Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>>()
        val preparedFiles = linkedMapOf<File, File>()
        info.resourceDirectories.forEach { directory ->
            val valuesRoot = File(workDir, "${directory.sourceSetName}/values")
            convertValues(directory, valuesRoot, preparedFiles)
            resources[directory.sourceSetName] = scanner.scan(
                directory.directory,
                valuesRoot,
                info.assetRelativePath,
            )
        }
        return PreparedResources(resources, preparedFiles, File(workDir, "assets"))
    }

    private fun convertValues(
        directory: ComposeResourceDirectory,
        valuesRoot: File,
        preparedFiles: MutableMap<File, File>,
    ) {
        directory.directory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.substringBefore('-') == "values" }
            .flatMap { it.listFiles().orEmpty().filter { file -> file.isFile && file.extension == "xml" } }
            .forEach { input ->
                val qualifierDir = input.parentFile.name
                val output = File(valuesRoot, "$qualifierDir/${input.nameWithoutExtension}.${directory.sourceSetName}.cvr")
                converter.convert(input, output)
                preparedFiles[input.canonicalFile] = output
            }
    }

    private fun generateSources(
        module: ModuleInfo,
        info: ComposeResourceInfo,
        prepared: PreparedResources,
        workDir: File,
    ): ComposeGeneratedSources {
        val generatedRoot = File(workDir, "generated")
        val generated = generator.generate(
            info,
            prepared.resources,
            resolveCommonSourceSetNames(module, info, context.modules.values),
            generatedRoot,
        )
        syncGeneratedSources(module, generatedRoot)
        return generated
    }

    private fun syncGeneratedSources(module: ModuleInfo, generatedRoot: File) {
        val targetDir = module.buildPathInfo.composeResourceGeneratedSourcePath
        runCatching {
            targetDir.clearDir()
            check(generatedRoot.copyRecursively(targetDir, overwrite = true)) {
                "Failed to copy Compose generated sources to ${targetDir.path}"
            }
        }.onFailure { exception ->
            logger.warn("Failed to update Compose generated sources for IDE: ${targetDir.path}")
            logger.debug("Compose generated source sync detail", exception)
        }
    }

    private fun compileGenerated(
        parentTask: CompileTask,
        module: ModuleInfo,
        generated: ComposeGeneratedSources,
        workDir: File,
    ): CompileResult {
        val files = generated.allFiles.map { CompileFile(CompileFile.Type.Kotlin, it, workDir, module) }
        val task = CompileTask(files, File(workDir, "classes"), parentTask)
        return kotlinCompiler.compile(
            context,
            module,
            task,
            logger,
            KotlinCompilerInvoker.Options(commonSourceFiles = generated.commonFiles, isCanAutoRetry = false),
        )
    }

    private fun successResult(
        task: CompileTask,
        module: ModuleInfo,
        prepared: PreparedResources,
        classOutputs: List<CompileOutput>,
    ): CompileResult {
        val assetOutputs = task.files.map { file -> prepareChangedAsset(file, module, prepared) }
        val outputs = classOutputs.map { it.copy(relativeModule = module) } + assetOutputs
        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }

    private fun prepareChangedAsset(
        input: CompileFile,
        module: ModuleInfo,
        prepared: PreparedResources,
    ): CompileOutput {
        val source = prepared.preparedValues[input.file.canonicalFile] ?: input.file
        val relative = if (source == input.file) input.file.relativeTo(input.baseDir) else source.relativeTo(source.parentFile.parentFile)
        val assetFile = File(prepared.assetRoot, "${module.composeResourceInfo!!.assetRelativePath}/${relative.path}")
        assetFile.parentFile.mkdirs()
        source.copyTo(assetFile, overwrite = true)
        return CompileOutput(CompileOutput.Type.Asset, assetFile, prepared.assetRoot, relativeModule = module)
    }

    private fun cancel(task: CompileTask, workDir: File): CompileResult {
        workDir.clearDir()
        return task.toCancelResult()
    }

    private fun moduleWorkDirName(module: ModuleInfo): String =
        "${module.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}_${module.moduleRootDir.absolutePath.hashCode()}"

    private data class PreparedResources(
        val resources: Map<String, Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>>,
        val preparedValues: Map<File, File>,
        val assetRoot: File,
    )

    companion object {
        internal fun mapGeneratedCompileFailure(task: CompileTask, generatedResult: CompileResult): CompileResult {
            val diagnostics = generatedResult.details
                .filter { it.isFailed }
                .flatMap { it.getFailure().errors }
                .ifEmpty { listOf(-1L to "Compile generated Compose resource accessors failed") }
            return CompileResult(
                task,
                task.files.map { Result.failure(com.sickworm.intellij.jugg.compiler.CompileError(it, diagnostics)) },
                emptyList(),
            )
        }

        internal fun resolveCommonSourceSetNames(
            owner: ModuleInfo,
            info: ComposeResourceInfo,
            modules: Collection<ModuleInfo>,
        ): Set<String> {
            val sourceSetModules = modules.filter {
                it.moduleRootDir == owner.moduleRootDir && it.moduleType == ModuleInfo.Type.Unknown
            }
            return info.resourceDirectories.mapNotNull { directory ->
                val sourceSetName = directory.sourceSetName
                if (sourceSetName == "androidMain") return@mapNotNull null
                val isCommon = sourceSetName == "commonMain" || sourceSetModules.any { module ->
                    module.name.endsWith(".$sourceSetName") || module.sourceDirs.any {
                        it.path.replace('\\', '/').contains("/src/$sourceSetName/")
                    }
                }
                sourceSetName.takeIf { isCommon }
            }.toSet()
        }
    }
}
