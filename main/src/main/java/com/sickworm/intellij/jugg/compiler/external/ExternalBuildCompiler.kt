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
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfoRequestItem
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
        // Every changed external input must resolve; a partially resolved round would run only some
        // builds and still report all files as compiled.
        val resolved = task.files.map { it to resolveBuild(it) }
        if (resolved.isEmpty()) {
            return task.failed("External build metadata not found")
        }
        resolved.firstOrNull { it.second == null }?.first?.let { unresolved ->
            return task.failed("External build metadata not found: ${unresolved.file.name}")
        }
        resolved.firstOrNull { !it.first.file.exists() }?.first?.let { missing ->
            return task.failed("External build source no longer exists: ${missing.file.name}")
        }
        val builds = resolved.map { (file, buildInfo) -> moduleOf(file) to buildInfo!! }.distinctBy {
            it.second.taskPath ?: "${it.second.type}:${it.second.inputDirs}"
        }
        builds.firstOrNull { !it.second.isSupported }?.second?.let { unsupported ->
            return task.failed(unsupported.unsupportedReason ?: "External build is not supported")
        }
        val gradleCommand = getFullBuildGradleCommand() ?: return task.failed("Gradle command not found")
        val buildNames = builds.map { it.second.type.name }.distinct().joinToString("/")
        logger.info("Compiling $buildNames sources with Gradle...")
        val requests = builds.map { (module, buildInfo) ->
            ExternalBuildInfoRequestItem(
                moduleName = module.name,
                moduleRootDir = module.moduleRootDir,
                buildVariant = module.buildVariant,
                taskPath = buildInfo.taskPath!!,
                type = buildInfo.type,
            )
        }
        val initScript = try {
            context.externalBuildInfoInitScript
        } catch (_: AbstractMethodError) {
            null
        }
        val runResult = runner.run(
            gradleCommand,
            requests,
            context.cmdCompileEnv,
            context.projectDir,
            File(context.tempCompileDir, "external_build_info"),
            initScript,
            task,
        )
        if (!runResult.isSuccess) {
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }
            return task.failed("External Gradle build failed")
        }
        if (runResult.updates.isNotEmpty()) {
            val updated = try {
                context.updateExternalBuildInfos(runResult.updates)
            } catch (_: AbstractMethodError) {
                false
            }
            if (!updated) {
                return task.failed("External build metadata update failed")
            }
        }

        val updatedBuilds = builds.map { (module, buildInfo) ->
            val updatedInfo = runResult.updates.singleOrNull { update ->
                update.moduleRootDir.absoluteFile.normalize() == module.moduleRootDir.absoluteFile.normalize() &&
                        update.buildVariant == module.buildVariant &&
                        update.previousTaskPath == buildInfo.taskPath &&
                        update.externalBuildInfo.type == buildInfo.type
            }?.externalBuildInfo ?: buildInfo
            (context.modules[module.name] ?: module) to updatedInfo
        }
        val collected = updatedBuilds.map { (module, buildInfo) -> collectArtifacts(task, module, buildInfo) }
        collected.firstNotNullOfOrNull { it.error }?.let { error ->
            return task.failed(error)
        }
        return CompileResult(
            task = task,
            details = task.files.map { Result.success(it) },
            outputs = collected.flatMap { it.outputs },
        )
    }

    private fun resolveBuild(file: CompileFile): ExternalBuildInfo? = resolveExternalBuild(moduleOf(file), file.file)

    /** Uses the latest module snapshot; changed files may still hold the module read before a refresh. */
    private fun moduleOf(file: CompileFile): ModuleInfo = context.modules[file.module.name] ?: file.module

    private fun getFullBuildGradleCommand(): String? {
        val command = try {
            context.fullBuildGradleCommand
        } catch (_: AbstractMethodError) {
            null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (context.scene != ICompileContext.Scene.INCREMENTAL_APK || command.containsGradleExecutable()) {
            return command
        }
        return "./gradlew $command"
    }

    private fun collectArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        buildInfo: ExternalBuildInfo,
    ): CollectedArtifacts {
        return when (buildInfo.type) {
            ExternalBuildType.Flutter -> collectFlutterArtifacts(task, module, buildInfo)
            ExternalBuildType.Cpp -> collectCppArtifacts(task, module, buildInfo)
        }
    }

    private fun CompileTask.failed(message: String): CompileResult {
        logger.warn(message)
        return allFailed(message)
    }

    private fun collectFlutterArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        buildInfo: ExternalBuildInfo,
    ): CollectedArtifacts {
        val assetsOutputDir = buildInfo.assetsOutputDir
        if (assetsOutputDir == null || !assetsOutputDir.isDirectory || !assetsOutputDir.canRead()) {
            return CollectedArtifacts("Flutter assets output directory is unavailable: $assetsOutputDir", emptyList())
        }
        val assets = File(assetsOutputDir, "flutter_assets").walkTopDown()
            .filter(File::isFile)
            .map { file -> CompileOutput(CompileOutput.Type.Asset, file, assetsOutputDir, relativeModule = module) }
            .toList()
        val native = collectFlutterNativeArtifacts(task, module, buildInfo.nativeOutput)
        native.error?.let { return native }
        val changeDetectionStart = System.nanoTime()
        val changedAssets = assets.filter { isChangedAsset(it, module) }
        logger.debug("Flutter asset change detection: files=${assets.size}, " +
                "sourceBytes=${assets.sumOf { it.file.length() }}, changed=${changedAssets.size}, " +
                "cost=${(System.nanoTime() - changeDetectionStart) / 1_000_000}ms")
        return CollectedArtifacts(
            error = null,
            outputs = changedAssets + native.outputs,
        )
    }

    private fun collectCppArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        buildInfo: ExternalBuildInfo,
    ): CollectedArtifacts {
        val nativeOutput = buildInfo.nativeOutput
        if (nativeOutput == null || !nativeOutput.isDirectory || !nativeOutput.canRead()) {
            return CollectedArtifacts("External build output directory is unavailable: $nativeOutput", emptyList())
        }
        return collectNativeArtifacts(task, module, nativeOutput)
    }

    /** Collects one Flutter native output, which is a Jar archive or a directory depending on the Flutter version. */
    private fun collectFlutterNativeArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        nativeOutput: File?,
    ): CollectedArtifacts {
        if (nativeOutput == null) {
            return CollectedArtifacts("Flutter native output is unavailable", emptyList())
        }
        return if (nativeOutput.isDirectory) {
            collectFlutterNativeDirArtifacts(task, module, nativeOutput)
        } else {
            collectFlutterNativeArchiveArtifacts(task, module, nativeOutput)
        }
    }

    private fun collectNativeArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        outputDir: File,
    ): CollectedArtifacts {
        val nativeRoot = File(task.outputDir, "external/${module.name.safeName()}/cpp-native")
        nativeRoot.deleteRecursively()
        val sourceFiles = outputDir.walkTopDown().filter { file ->
            file.isFile && file.extension == "so" && file.findAbi() != null
        }.toList()
        val allOutputs = sourceFiles.mapNotNull { source ->
            val abi = source.findAbi() ?: return@mapNotNull null
            val output = File(nativeRoot, "$abi/${source.name}")
            output.parentFile.mkdirs()
            source.copyTo(output, overwrite = true)
            CompileOutput(CompileOutput.Type.NativeLib, output, nativeRoot, relativeModule = module)
        }.distinctBy {
            it.relativeFile.invariantSeparatorsPath
        }
        return CollectedArtifacts(
            error = null,
            outputs = allOutputs.filter { isChangedNativeLib(it, module) },
        )
    }

    private fun collectFlutterNativeDirArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        nativeDir: File,
    ): CollectedArtifacts {
        if (!nativeDir.isDirectory || !nativeDir.canRead()) {
            return CollectedArtifacts("Flutter native output is unavailable: $nativeDir", emptyList())
        }
        val nativeRoot = File(task.outputDir, "external/${module.name.safeName()}/flutter-native")
        nativeRoot.deleteRecursively()
        val allOutputs = nativeDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name in abiFolders }
            .flatMap { abiDir ->
                abiDir.listFiles().orEmpty().filter { it.isFile && it.extension == "so" }
                    .map { source -> abiDir.name to source }
            }
            .map { (abi, source) ->
                val output = File(nativeRoot, "$abi/${source.name}")
                output.parentFile.mkdirs()
                source.copyTo(output, overwrite = true)
                CompileOutput(CompileOutput.Type.NativeLib, output, nativeRoot, relativeModule = module)
            }
            .distinctBy { it.relativeFile.invariantSeparatorsPath }
        return CollectedArtifacts(
            error = null,
            outputs = allOutputs.filter { isChangedNativeLib(it, module) },
        )
    }

    private fun collectFlutterNativeArchiveArtifacts(
        task: CompileTask,
        module: ModuleInfo,
        nativeOutput: File,
    ): CollectedArtifacts {
        if (!nativeOutput.isFile || !nativeOutput.canRead()) {
            return CollectedArtifacts("Flutter native output is unavailable: $nativeOutput", emptyList())
        }
        val nativeRoot = File(task.outputDir, "external/${module.name.safeName()}/flutter-native")
        nativeRoot.deleteRecursively()
        val outputs = mutableListOf<CompileOutput>()
        val entryNames = mutableSetOf<String>()
        return try {
            ZipFile(nativeOutput).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("lib/")) {
                        continue
                    }
                    val segments = entry.name.split('/')
                    if (segments.size != 3 || segments[1] !in abiFolders ||
                        !segments[2].endsWith(".so") || segments[2].contains("..") || segments[2].contains('\\')
                    ) {
                        return CollectedArtifacts("Flutter native output contains an unsafe entry: ${entry.name}", emptyList())
                    }
                    val relativePath = "${segments[1]}/${segments[2]}"
                    if (!entryNames.add(relativePath)) {
                        return CollectedArtifacts("Flutter native output contains duplicate entry: ${entry.name}", emptyList())
                    }
                    val output = File(nativeRoot, relativePath)
                    output.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                    outputs += CompileOutput(CompileOutput.Type.NativeLib, output, nativeRoot, relativeModule = module)
                }
            }
            CollectedArtifacts(
                error = null,
                outputs = outputs.filter { isChangedNativeLib(it, module) },
            )
        } catch (e: Exception) {
            logger.debug("Read Flutter native output $nativeOutput failed", e)
            CollectedArtifacts("Flutter native output could not be read: $nativeOutput", emptyList())
        }
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

    private fun String.containsGradleExecutable(): Boolean {
        return Regex("(^|\\s)([^\\s/\\\\]+[/\\\\])?(gradle|gradlew|gradle\\.bat|gradlew\\.bat)(\\s|$)")
            .containsMatchIn(this)
    }

    private data class CollectedArtifacts(
        val error: String?,
        val outputs: List<CompileOutput>,
    )

    companion object {
        private val abiFolders = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
}
