package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.BaseCompiler
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.toCancelResult
import com.sickworm.intellij.jugg.gradle.compile.crc32
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.zip.ZipFile

/** Compiles Flutter and C++ sources through their Gradle tasks and exposes deployable artifacts. */
class ExternalBuildCompiler(
    context: ICompileContext,
    parent: Disposable,
) : BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.ExternalBuildSource)

    private val runner = ExternalBuildTaskRunner(logger)

    override fun doCompile(task: CompileTask): CompileResult {
        val builds = task.files.mapNotNull(::resolveBuild).distinctBy {
            it.second.taskPath ?: "${it.second.type}:${it.second.sourceDirs}"
        }
        if (builds.isEmpty()) {
            return task.allFailed("External build metadata not found")
        }
        builds.firstOrNull { !it.second.isSupported }?.second?.let { unsupported ->
            return task.allFailed(unsupported.unsupportedReason ?: "External build is not supported")
        }
        val gradleCommand = task.gradleCommand ?: return task.allFailed("Gradle command not found")
        val buildNames = builds.map { it.second.type.name }.distinct().joinToString("/")
        logger.info("Compiling $buildNames sources with Gradle...")
        if (!runner.run(
                gradleCommand,
                builds.mapNotNull { it.second.taskPath },
                context.cmdCompileEnv,
                context.projectDir,
                task,
            )) {
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }
            return task.allFailed("External Gradle build failed")
        }

        val collected = builds.map { (module, buildInfo) ->
            collectArtifacts(task, module, buildInfo)
        }
        val missing = collected.firstOrNull { it.discoveredCount == 0 }
        if (missing != null) {
            return task.allFailed("External build produced no deployable artifacts")
        }
        return CompileResult(
            task = task,
            details = task.files.map { Result.success(it) },
            outputs = collected.flatMap { it.outputs },
        )
    }

    private fun resolveBuild(file: CompileFile): Pair<ModuleInfo, ExternalBuildInfo>? {
        val extension = file.file.extension.lowercase()
        val info = file.module.externalBuildInfos.firstOrNull { buildInfo ->
            val supportsExtension = when (buildInfo.type) {
                ExternalBuildType.Flutter -> extension == "dart"
                ExternalBuildType.Cpp -> extension in cppSourceExtensions
            }
            supportsExtension && buildInfo.sourceDirs.any { file.file.toPath().startsWith(it.toPath()) }
        }
        return info?.let { file.module to it }
    }

    private fun collectArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        buildInfo: ExternalBuildInfo,
    ): CollectedArtifacts {
        val outputDir = buildInfo.outputDir
        if (outputDir == null || !outputDir.isDirectory) {
            return CollectedArtifacts(0, emptyList())
        }
        return when (buildInfo.type) {
            ExternalBuildType.Flutter -> collectFlutterArtifacts(task, module, outputDir)
            ExternalBuildType.Cpp -> collectNativeArtifacts(task, module, outputDir, false)
        }
    }

    private fun collectFlutterArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        outputDir: File,
    ): CollectedArtifacts {
        val assets = File(outputDir, "flutter_assets").walkTopDown()
            .filter(File::isFile)
            .map { file -> CompileOutput(CompileOutput.Type.Asset, file, outputDir, relativeModule = module) }
            .toList()
        val native = collectNativeArtifacts(task, module, outputDir, true)
        val changedAssets = assets.filter { isChangedAsset(it, module) }
        return CollectedArtifacts(
            discoveredCount = assets.size + native.discoveredCount,
            outputs = changedAssets + native.outputs,
        )
    }

    private fun collectNativeArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        outputDir: File,
        isFlutter: Boolean,
    ): CollectedArtifacts {
        val nativeRoot = File(task.outputDir, "external/${module.name.safeName()}/native")
        val sourceFiles = outputDir.walkTopDown().filter { file ->
            file.isFile && file.extension == "so" && file.findAbi() != null
        }.toList()
        val outputs = sourceFiles.mapNotNull { source ->
            val abi = source.findAbi() ?: return@mapNotNull null
            val outputName = if (isFlutter && !source.name.startsWith("lib")) "lib${source.name}" else source.name
            val output = File(nativeRoot, "$abi/$outputName")
            output.parentFile.mkdirs()
            source.copyTo(output, overwrite = true)
            CompileOutput(CompileOutput.Type.NativeLib, output, nativeRoot, relativeModule = module)
        }.distinctBy {
            it.relativeFile.invariantSeparatorsPath
        }.filter { isChangedNativeLib(it, module) }
        return CollectedArtifacts(sourceFiles.size, outputs)
    }

    private fun isChangedAsset(output: CompileOutput, module: ModuleInfo): Boolean {
        val deployPath = "assets/${output.relativeFile.invariantSeparatorsPath}"
        val targets = getTargetApkPaths(module)
        val deployed = context.deployedFiles.filter {
            it.type == CompileOutput.Type.Asset && it.relativeFile.invariantSeparatorsPath == deployPath
        }
        if (targets.isEmpty()) {
            return deployed.isEmpty() || deployed.any { it.file.crc32 != output.file.crc32 }
        }
        return targets.any { target ->
            val targetFiles = deployed.filter { it.apkPath == target }
            targetFiles.isEmpty() || targetFiles.any { it.file.crc32 != output.file.crc32 }
        }
    }

    private fun isChangedNativeLib(output: CompileOutput, module: ModuleInfo): Boolean {
        val entryName = "lib/${output.relativeFile.invariantSeparatorsPath}"
        val targets = getTargetApkPaths(module)
        if (targets.isEmpty()) {
            return true
        }
        return targets.any { apkPath ->
            try {
                ZipFile(apkPath).use { apk ->
                    apk.getEntry(entryName)?.crc != output.file.crc32
                }
            } catch (e: Exception) {
                logger.debug("Read native entry $entryName from $apkPath failed", e)
                true
            }
        }
    }

    private fun getTargetApkPaths(module: ModuleInfo): List<String> {
        return context.moduleBelongsApkMap.getAllBelongsApk(module)
            .ifEmpty { listOfNotNull(context.moduleBelongsApkMap.getBelongsApk(module)) }
            .map { it.apkFile.path }
            .distinct()
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        return CompileResult(task, emptyList(), emptyList())
    }

    private fun File.findAbi(): String? {
        return generateSequence(parentFile) { it.parentFile }
            .map { it.name }
            .firstOrNull { it in abiFolders }
    }

    private fun String.safeName(): String = replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private data class CollectedArtifacts(
        val discoveredCount: Int,
        val outputs: List<CompileOutput>,
    )

    companion object {
        private val abiFolders = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        private val cppSourceExtensions = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx")
    }
}
