package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.compiler.source.kotlin.KspProcessorRunner
import java.io.File
import java.util.Locale

/** Compares current-round ARouter semantics before running the project's aggregate processors. */
class ARouterJuggAptProcessor : BaseJuggAptProcessor() {
    override val id = "ARouter"
    override val failClosed = true
    private val generatedRoots = linkedSetOf<File>()
    private val compatibility = ARouterProcessorCompatibility()
    override fun ownsGeneratedFile(file: CompileFile): Boolean =
        generatedRoots.any { file.file.canonicalFile.toPath().startsWith(it.toPath()) }

    override fun process(context: ICompileContext, module: ModuleInfo, allCompileFiles: List<CompileFile>,
        analysis: AptSourceAnalysis): List<CompileFile> {
        if (context.customCompilers.filterIsInstance<IJuggAptProcessor>().any { it.id == id }) {
            context.logger.debug("ARouter built-in processor skipped for ${module.name}: external processor configured")
            return emptyList()
        }
        val checkpoint = GeneratedSourceCheckpoint(File(context.incrementalDataDir, "arouter/${module.name}/${module.buildVariant}"))
        generatedRoots += checkpoint.sourceRoot.canonicalFile
        val currentFiles = allCompileFiles.filter { it.file.isFile && it.type in listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin) && !ownsGeneratedFile(it) }
        if (currentFiles.isEmpty()) return emptyList()
        val prepared = try {
            prepare(context, module, currentFiles, checkpoint, analysis) ?: return emptyList()
        } catch (failure: Throwable) {
            logSkipped(context, module, currentFiles.map { it.file }, failure)
            return emptyList()
        }
        try {
            return runGeneration(context, module, allCompileFiles, checkpoint, prepared)
        } catch (failure: Throwable) {
            context.logger.warn("ARouter processor failed for ${module.name}: ${failure.message}", failure)
            throw IllegalStateException("ARouter processor failed; see the ARouter warning log")
        }
    }

    private data class Preparation(val sources: List<File>, val priorNames: List<String>)

    /** Checks every round input before preparing complete aggregate sources. */
    private fun prepare(context: ICompileContext, module: ModuleInfo, files: List<CompileFile>,
        checkpoint: GeneratedSourceCheckpoint, analysis: AptSourceAnalysis): Preparation? {
        val timingStart = System.nanoTime()
        try {
            val resolver = AptSemanticResolver(context, module, analysis)
            val semantics = ARouterInputSemantics(analysis, resolver)
            val baseline by lazy { ARouterBaseline(module, analysis) }
            var changed = false
            val failures = mutableListOf<Pair<File, Throwable>>()
            files.forEach { input ->
                try {
                    if (semantics.hasSupportedAnnotation(input.file)) {
                        analysis.count("arouterCandidates")
                        if (inputsChanged(context, input.file, analysis, semantics, baseline)) changed = true
                    }
                } catch (failure: Throwable) {
                    failures += input.file to failure
                }
            }
            if (failures.isNotEmpty()) {
                failures.forEach { (file, failure) -> logSkipped(context, module, listOf(file), failure) }
                return null
            }
            if (!changed) {
                context.logger.debug("ARouter processor skipped for ${module.name}: current-round inputs unchanged")
                return null
            }
            // Keep unannotated sources too, so aggregate KAPT can resolve new Kotlin dependencies.
            val sources = module.sourceDirs.filter { it.exists() }.flatMap { root ->
                sourceFiles(root).filter { it.extension in listOf("java", "kt") &&
                    !it.canonicalFile.toPath().startsWith(checkpoint.sourceRoot.canonicalFile.toPath()) }
            }.distinctBy { it.canonicalPath }
            // Validate the entire aggregate before invoking a fail-closed processor.
            sources.filter { analysis.mentions(it, ARouterInputSemantics.PACKAGE) }.forEach { source ->
                try { semantics.current(source) } catch (failure: Throwable) { failures += source to failure }
            }
            if (failures.isNotEmpty()) {
                failures.forEach { (file, failure) -> logSkipped(context, module, listOf(file), failure) }
                return null
            }
            val ownedSources = checkpoint.sourceRoot.exists()
            val roots = if (ownedSources) listOf(checkpoint.sourceRoot) else context.getGeneratedSourcePaths(module)
            // Gradle outputs may be shared; other processors' classes must not enter ARouter shrink checks.
            val priorNames = roots.filter { it.exists() }.flatMap { root ->
                sourceFiles(root).filter { it.extension in listOf("java", "kt") && (ownedSources || it.name.contains("ARouter")) }
                    .map { generatedIdentity(it) }
            }.distinct()
            return Preparation(sources, priorNames)
        } finally {
            analysis.recordTime("arouterPrepare", System.nanoTime() - timingStart)
        }
    }

    /** Compares actual annotated inputs against their current business classes. */
    private fun inputsChanged(context: ICompileContext, file: File, analysis: AptSourceAnalysis,
        semantics: ARouterInputSemantics, baseline: ARouterBaseline): Boolean {
        val timingStart = System.nanoTime()
        try {
            val current = semantics.current(file)
            val old = baseline.classes(file)
            baseline.requireReliableAssociation(context, file, old, current)
            return current != semantics.baseline(old)
        } finally {
            analysis.recordTime("arouterCompare", System.nanoTime() - timingStart)
        }
    }

    /** File.walk silently ignores unreadable directories; generation must not treat them as empty. */
    private fun sourceFiles(root: File): List<File> {
        check(root.isDirectory) { "Source directory is not readable: $root" }
        return root.walkTopDown().onEnter { directory ->
            check(directory.canRead() && directory.listFiles() != null) { "Source directory is not readable: $directory" }
            true
        }.onFail { file, failure -> throw IllegalStateException("Cannot read source directory $file", failure) }
            .filter { it.isFile }.toList()
    }

    private fun runGeneration(context: ICompileContext, module: ModuleInfo, files: List<CompileFile>,
        checkpoint: GeneratedSourceCheckpoint, prepared: Preparation): List<CompileFile> {
        val options = module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty()
        val paths = (module.annotationProcessorDependencies + module.kaptDependencies).map { it.file.canonicalFile }.distinct()
        val kspPaths = module.kspInfo?.processorDependencies.orEmpty().map { it.file.canonicalFile }.distinct()
        val kspProcessor = compatibility.projectKspProcessor(kspPaths, module)
        if (kspProcessor != null) {
            check(!compatibility.hasAptProcessor(paths)) { "Both APT/KAPT and KSP declare ARouter processors for ${module.name}" }
            val kspOptions = module.kspInfo?.arguments.orEmpty()
            check(kspOptions.containsKey("AROUTER_MODULE_NAME") || kspOptions.containsKey("MODULE_NAME")) {
                "AROUTER_MODULE_NAME or MODULE_NAME is missing for ${module.name}"
            }
            return generate(context, module, prepared, kspProcessor, kspPaths, kspOptions, checkpoint, true)
        }
        val original = compatibility.projectProcessor(paths, module)
        check(options.containsKey("AROUTER_MODULE_NAME") || options.containsKey("moduleName")) { "AROUTER_MODULE_NAME or moduleName is missing for ${module.name}" }
        val work = File(context.tempCompileDir, "arouter/${module.name}")
        val selected = compatibility.selectProcessor(context, module, original, paths, CompileTask(files, work, CompileStatusHolder.DEFAULT))
        val effectivePaths = compatibility.replaceProcessorJar(paths, original.jar, selected.jar)
        val effectiveContext = compatibility.processorContext(context, original.jar, selected.jar)
        return generate(effectiveContext, module, prepared, selected, effectivePaths, options, checkpoint)
    }

    private fun generatedIdentity(file: File): String {
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(file.readText())?.groupValues?.get(1)
        return listOfNotNull(pkg, file.nameWithoutExtension).joinToString(".")
    }

    private fun logSkipped(context: ICompileContext, module: ModuleInfo, files: List<File>, failure: Throwable) {
        context.logger.debug("ARouter detection failed for ${module.name}, files=$files: ${failure.message}; " +
                "generation skipped for this module round; indexes may be stale; run a full Gradle build to restore consistency", failure)
    }

    /** Generation failures and class removal retain the existing fail-closed contract. */
    private fun generate(context: ICompileContext, module: ModuleInfo, prepared: Preparation,
        processor: ARouterProcessorCompatibility.ProjectProcessor, paths: List<File>, options: Map<String, String>,
        checkpoint: GeneratedSourceCheckpoint, ksp: Boolean = false): List<CompileFile> {
        val start = System.nanoTime()
        val moduleName = options["AROUTER_MODULE_NAME"] ?: options["MODULE_NAME"] ?: options.getValue("moduleName")
        context.logger.info("ARouter processor started for $moduleName (${module.name}).")
        try {
            val work = File(context.tempCompileDir, "arouter/${module.name}")
            val generated = if (ksp) KspProcessorRunner().generate(context, module, prepared.sources, paths, processor.names, options, work)
                else AnnotationProcessorRunner().generate(context, module, prepared.sources, paths, processor.names, options, work)
            val outputs = sourceFiles(generated).filter { it.extension in listOf("java", "kt") }
            val names = outputs.map { it.relativeTo(generated).invariantSeparatorsPath }.sorted()
            val removed = prepared.priorNames - outputs.map { generatedIdentity(it) }.toSet()
            check(removed.isEmpty()) { "ARouter generated classes were removed (${removed.joinToString()}); run a full Gradle build" }
            checkpoint.publish(generated)
            syncGeneratedSources(context, module, checkpoint.sourceRoot, names, ksp)
            // Old input signatures are no longer a baseline; comparison uses current business classes.
            listOf("inputs.json", "checkpoint.txt", "inputs.pending", "checkpoint.pending").forEach { name ->
                val obsolete = File(checkpoint.folder, name)
                if (obsolete.exists() && !obsolete.delete()) context.logger.warn("Cannot remove obsolete ARouter metadata $obsolete")
            }
            context.logger.debug("ARouter generated ${names.size} sources for $moduleName")
            return names.map { name ->
                val file = File(checkpoint.sourceRoot, name)
                context.logger.debug("ARouter generated source: ${file.absolutePath}")
                CompileFile(if (file.extension == "kt") CompileFile.Type.Kotlin else CompileFile.Type.Java, file, checkpoint.sourceRoot, module)
            }
        } finally {
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            context.logger.info("ARouter processor finished for ${module.name} in " +
                    "${String.format(Locale.ROOT, "%.2f", seconds)} seconds")
        }
    }

    /** Mirrors only current outputs for IDE navigation; compilation keeps using the published sources. */
    private fun syncGeneratedSources(context: ICompileContext, module: ModuleInfo, sourceRoot: File,
        names: List<String>, ksp: Boolean) {
        val generatedRoot = module.buildPathInfo.generatedSourcePath
        try {
            val target = ideGeneratedSourceDir(context, module, names, ksp)
            names.forEach { name -> File(sourceRoot, name).copyTo(File(target, name), overwrite = true) }
        } catch (failure: Exception) {
            context.logger.warn("Failed to update ARouter generated sources for IDE in ${module.name}/${module.buildVariant} at $generatedRoot; " +
                    "compilation continues with Jugg generated sources", failure)
        }
    }

    private fun ideGeneratedSourceDir(context: ICompileContext, module: ModuleInfo, names: List<String>, ksp: Boolean): File {
        val generatedRoot = module.buildPathInfo.generatedSourcePath
        if (ksp) {
            val kspRoot = File(generatedRoot, "ksp").canonicalFile.toPath()
            return module.sourceDirs.firstOrNull { it.name in listOf("java", "kotlin") &&
                it.canonicalFile.toPath().startsWith(kspRoot) }?.parentFile
                ?: File(generatedRoot, "ksp/${module.buildVariant}")
        }
        val roots = context.getGeneratedSourcePaths(module).filter {
            it.canonicalFile.toPath().startsWith(generatedRoot.canonicalFile.toPath())
        }
        return roots.firstOrNull { root -> names.any { File(root, it).isFile } }
            ?: File(generatedRoot, if (module.kaptDependencies.isNotEmpty()) "source/kapt/${module.buildVariant}"
                else "ap_generated_sources/${module.buildVariant}/out")
    }
}
