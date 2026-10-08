package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.BaseCompiler
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.resolveApkOwnerModule
import com.sickworm.intellij.jugg.compiler.toCancelResult
import com.sickworm.intellij.jugg.gradle.compile.crc32
import com.sickworm.intellij.jugg.project.data.ExternalBuildGeneratedLanguage
import com.sickworm.intellij.jugg.project.data.ExternalBuildGeneratedSourceDir
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfoRequestItem
import com.sickworm.intellij.jugg.project.data.ExternalBuildPrerequisite
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
        val resolved = task.files.map { it to resolveBuilds(it) }
        if (resolved.isEmpty()) {
            return task.failed("External build metadata not found")
        }
        resolved.firstOrNull { it.second.isEmpty() }?.first?.let { unresolved ->
            return task.failed("External build metadata not found: ${unresolved.file.name}")
        }
        resolved.firstOrNull { !it.first.file.exists() }?.first?.let { missing ->
            return task.failed("External build source no longer exists: ${missing.file.name}")
        }
        val targets = resolved.flatMap { it.second }
        targets.firstOrNull { !it.buildInfo.isSupported }?.buildInfo?.let { unsupported ->
            return task.failed(unsupported.unsupportedReason ?: "External build is not supported")
        }
        val builds = targets.distinctBy { target ->
            listOf(
                target.module.moduleRootDir.absoluteFile.normalize().path,
                target.module.buildVariant,
                target.buildInfo.taskPath,
                target.buildInfo.type,
            )
        }
        val gradleCommand = getFullBuildGradleCommand() ?: return task.failed("Gradle command not found")
        val buildNames = builds.map { it.buildInfo.type.name }.distinct().joinToString("/")
        logger.info("Compiling $buildNames sources with Gradle...")
        val changedFiles = task.files.map { it.file }
        val generatedSnapshot = snapshotGeneratedSourceDirs(
            builds.flatMap { (_, buildInfo) ->
                matchedPrerequisites(buildInfo, changedFiles).flatMap { it.generatedSourceDirs }
            },
        )
        val requests = builds.map { (module, buildInfo) ->
            // C++ outputs are stripped with the APK owner configuration, so the collector needs the
            // owning app or dynamic feature module instead of assuming every library belongs to app.
            val apkOwner = if (buildInfo.type == ExternalBuildType.Cpp) {
                context.resolveApkOwnerModule(module)
            } else {
                null
            }
            val matched = matchedPrerequisites(buildInfo, changedFiles)
            ExternalBuildInfoRequestItem(
                moduleName = module.name,
                moduleRootDir = module.moduleRootDir,
                buildVariant = module.buildVariant,
                taskPath = buildInfo.taskPath!!,
                type = buildInfo.type,
                apkOwnerModuleRootDir = apkOwner?.moduleRootDir,
                apkOwnerBuildVariant = apkOwner?.buildVariant,
                prerequisiteTaskPaths = matched.map { it.taskPath },
                prerequisiteBeforeNativePrefixes = matched.flatMap { it.beforeNativeTaskPrefixes }
                    .distinct(),
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
            val update = runResult.updates.singleOrNull { update ->
                update.moduleRootDir.absoluteFile.normalize() == module.moduleRootDir.absoluteFile.normalize() &&
                        update.buildVariant == module.buildVariant &&
                        update.previousTaskPath == buildInfo.taskPath &&
                        update.externalBuildInfo.type == buildInfo.type
            }
            val resolvedInfo = update?.externalBuildInfo ?: buildInfo
            ResolvedBuild(
                module = context.modules[module.name] ?: module,
                buildInfo = resolvedInfo,
                strippedNativeOutput = update?.strippedNativeOutput,
                matchedPrerequisites = matchedPrerequisites(resolvedInfo, task.files.map { it.file }),
            )
        }
        val collected = updatedBuilds.map { collectArtifacts(task, it, generatedSnapshot) }
        collected.firstNotNullOfOrNull { it.error }?.let { error ->
            return task.failed(error)
        }
        return CompileResult(
            task = task,
            details = task.files.map { Result.success(it) },
            outputs = collected.flatMap { it.outputs },
        )
    }

    private fun resolveBuilds(file: CompileFile): List<ExternalBuildTarget> {
        val modules = context.modules.values.toList()
        val hasAnchor = modules.any { module ->
            module.name == file.module.name &&
                    module.moduleRootDir.absoluteFile.normalize() == file.module.moduleRootDir.absoluteFile.normalize()
        }
        return resolveExternalBuilds(if (hasAnchor) modules else modules + file.module, file.file)
    }

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
        build: ResolvedBuild,
        generatedSnapshot: GeneratedSourceSnapshot,
    ): CollectedArtifacts {
        val native = when (build.buildInfo.type) {
            ExternalBuildType.Flutter -> collectFlutterArtifacts(task, build.module, build.buildInfo)
            ExternalBuildType.Cpp -> collectCppArtifacts(build.module, build.strippedNativeOutput)
        }
        native.error?.let { return native }
        val generated = collectGeneratedSourceArtifacts(build, generatedSnapshot)
        generated.error?.let { return generated }
        return CollectedArtifacts(error = null, outputs = native.outputs + generated.outputs)
    }

    /**
     * Collects Kotlin/Java files from declared codegen output roots after the prerequisite task.
     * Missing directories fail the round; an empty tree is a successful no-op. Files whose size and
     * timestamp match the pre-task snapshot stay out of SourceCompiler.
     */
    private fun collectGeneratedSourceArtifacts(
        build: ResolvedBuild,
        generatedSnapshot: GeneratedSourceSnapshot,
    ): CollectedArtifacts {
        if (build.matchedPrerequisites.isEmpty()) {
            return CollectedArtifacts(error = null, outputs = emptyList())
        }
        val generated = collectGeneratedSourceOutputs(
            build.module,
            build.matchedPrerequisites.flatMap { it.generatedSourceDirs },
            generatedSnapshot,
        )
        if (generated.error == null) {
            logger.info("Generated source change detection: files=${generated.scannedCount}, " +
                    "changed=${generated.outputs.size}")
        }
        return CollectedArtifacts(generated.error, generated.outputs)
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

    /**
     * Collects only the stripped output produced by this invocation. The module merge directory holds
     * unstripped libraries that are not what the APK packages, so it is never used as a fallback.
     */
    private fun collectCppArtifacts(module: ModuleInfo, strippedNativeOutput: File?): CollectedArtifacts {
        if (strippedNativeOutput == null) {
            return CollectedArtifacts(
                "Stripped native output is unavailable for ${module.name}, run a normal Gradle build to recover",
                emptyList(),
            )
        }
        if (!strippedNativeOutput.isDirectory || !strippedNativeOutput.canRead()) {
            return CollectedArtifacts(
                "Stripped native output directory is unavailable: $strippedNativeOutput",
                emptyList(),
            )
        }
        return collectNativeArtifacts(module, strippedNativeOutput)
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

    /** Collects the C++ native libraries of one invocation-owned output directory. */
    private fun collectNativeArtifacts(
        module: ModuleInfo,
        outputDir: File,
    ): CollectedArtifacts {
        val allOutputs = outputDir.walkTopDown().filter { file ->
            file.isFile && file.extension == "so" && file.findAbi() != null
        }.map { source ->
            CompileOutput(CompileOutput.Type.NativeLib, source, outputDir, relativeModule = module)
        }.distinctBy {
            it.relativeFile.invariantSeparatorsPath
        }.toList()
        return CollectedArtifacts(
            error = null,
            outputs = filterChangedNativeArtifacts(allOutputs, module, outputDir),
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
            outputs = filterChangedNativeArtifacts(allOutputs, module, nativeDir),
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
                outputs = filterChangedNativeArtifacts(outputs, module, nativeOutput),
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

    private fun filterChangedNativeArtifacts(
        outputs: List<CompileOutput>,
        module: ModuleInfo,
        outputSource: File,
    ): List<CompileOutput> {
        val changed = outputs.filter { isChangedNativeLib(it, module) }
        logger.debug("Native library change detection: module=${module.name}, output=$outputSource, " +
                "candidates=${outputs.size}, changed=${changed.size}, " +
                "skipped=${outputs.size - changed.size}")
        return changed
    }

    /**
     * Compare each target APK against its deployed library first, then its APK baseline. A matching
     * baseline cannot suppress a library that was previously overlaid with different content.
     * Missing deployed files and unreadable APKs conservatively require another deployment.
     */
    private fun isChangedNativeLib(output: CompileOutput, module: ModuleInfo): Boolean {
        val entryName = "lib/${output.relativeFile.invariantSeparatorsPath}"
        val targets = getTargetApkPaths(module)
        if (targets.isEmpty()) {
            logger.debug("Keep native library $entryName for module ${module.name}: no target APK")
            return true
        }
        val deployed = context.deployedFiles.filter {
            it.type == CompileOutput.Type.NativeLib &&
                it.relativeFile.invariantSeparatorsPath == entryName
        }
        val checksum = output.file.crc32
        val matchedSources = mutableListOf<String>()
        val changed = targets.any { apkPath ->
            val previous = deployed.find { it.apkPath == apkPath || apkPath in it.targetApkPaths }
            if (previous != null) {
                val matches = previous.file.isFile && previous.file.crc32 == checksum
                if (matches) matchedSources += "$apkPath (deployed)"
                return@any !matches
            }
            try {
                val matches = ZipFile(apkPath).use { apk -> apk.getEntry(entryName)?.crc == checksum }
                if (matches) matchedSources += "$apkPath (APK baseline)"
                !matches
            } catch (e: Exception) {
                logger.debug("Read native entry $entryName from $apkPath failed", e)
                true
            }
        }
        if (!changed) {
            logger.debug("Skip unchanged native library $entryName for module ${module.name}, crc32=$checksum: " +
                    matchedSources.joinToString())
        }
        return changed
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

    /** One external build after its invocation-scoped metadata has been applied. */
    private data class ResolvedBuild(
        val module: ModuleInfo,
        val buildInfo: ExternalBuildInfo,
        val strippedNativeOutput: File?,
        val matchedPrerequisites: List<ExternalBuildPrerequisite>,
    )

    companion object {
        private val abiFolders = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
}

/** Result of walking declared codegen output roots. */
internal data class GeneratedSourceCollection(
    val error: String?,
    val outputs: List<CompileOutput>,
    val scannedCount: Int = 0,
)

/** lastModified + length of generated sources captured before the Gradle invocation. */
internal data class GeneratedSourceFingerprint(
    val lastModified: Long,
    val length: Long,
)

internal data class GeneratedSourceSnapshot(
    val files: Map<String, GeneratedSourceFingerprint> = emptyMap(),
)

/**
 * Walks declared codegen output roots and returns Kotlin/Java compile outputs. A missing or
 * unreadable directory fails; an empty tree is success with no files. [previous] is the pre-task
 * snapshot: unchanged files are omitted so SourceCompiler does not rebuild an entire codegen tree.
 */
internal fun collectGeneratedSourceOutputs(
    module: ModuleInfo,
    dirs: List<ExternalBuildGeneratedSourceDir>,
    previous: GeneratedSourceSnapshot,
): GeneratedSourceCollection {
    val outputs = mutableListOf<CompileOutput>()
    var scannedCount = 0
    dirs.forEach { dir ->
        val listed = listGeneratedSourceFiles(dir)
            ?: return GeneratedSourceCollection(
                "Generated source directory is unavailable: ${dir.directory}",
                emptyList(),
            )
        val outputType = when (dir.language) {
            ExternalBuildGeneratedLanguage.Kotlin -> CompileOutput.Type.Kotlin
            ExternalBuildGeneratedLanguage.Java -> CompileOutput.Type.Java
        }
        listed.forEach { file ->
            scannedCount += 1
            if (isGeneratedSourceChanged(file, previous)) {
                outputs += CompileOutput(outputType, file, dir.directory, relativeModule = module)
            }
        }
    }
    return GeneratedSourceCollection(error = null, outputs = outputs, scannedCount = scannedCount)
}

/** Records existing generated sources so post-task collection can drop files codegen did not rewrite. */
internal fun snapshotGeneratedSourceDirs(
    dirs: List<ExternalBuildGeneratedSourceDir>,
): GeneratedSourceSnapshot {
    val files = linkedMapOf<String, GeneratedSourceFingerprint>()
    dirs.forEach { dir ->
        listGeneratedSourceFiles(dir).orEmpty().forEach { file ->
            files[file.normalizedAbsolutePath()] = GeneratedSourceFingerprint(
                lastModified = file.lastModified(),
                length = file.length(),
            )
        }
    }
    return GeneratedSourceSnapshot(files)
}

private fun listGeneratedSourceFiles(dir: ExternalBuildGeneratedSourceDir): List<File>? {
    val directory = dir.directory
    if (!directory.isDirectory || !directory.canRead()) {
        return null
    }
    val extension = when (dir.language) {
        ExternalBuildGeneratedLanguage.Kotlin -> "kt"
        ExternalBuildGeneratedLanguage.Java -> "java"
    }
    return directory.walkTopDown().filter { file ->
        file.isFile && file.extension.equals(extension, ignoreCase = true)
    }.toList()
}

private fun isGeneratedSourceChanged(file: File, previous: GeneratedSourceSnapshot): Boolean {
    val prior = previous.files[file.normalizedAbsolutePath()] ?: return true
    return prior.lastModified != file.lastModified() || prior.length != file.length()
}

private fun File.normalizedAbsolutePath(): String = absoluteFile.normalize().path
