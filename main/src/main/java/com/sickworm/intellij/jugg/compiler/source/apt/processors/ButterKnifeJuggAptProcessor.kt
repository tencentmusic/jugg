package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.github.javaparser.ast.expr.AnnotationExpr
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.apt.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import java.io.File
import java.util.Locale

/** Runs the project's isolated ButterKnife processor only for related round inputs. */
class ButterKnifeJuggAptProcessor : IJuggAptProcessor {
    override val id = "ButterKnife"
    override val failClosed = true
    private val generatedRoots = linkedSetOf<File>()
    private val companionInputs = linkedSetOf<File>()
    override fun ownsGeneratedFile(file: CompileFile) = file.file.canonicalFile in companionInputs ||
        generatedRoots.any { file.file.canonicalFile.toPath().startsWith(it.toPath()) }

    override fun process(context: ICompileContext, module: ModuleInfo, allCompileFiles: List<CompileFile>,
        analysis: AptSourceAnalysis): List<CompileFile> {
        val paths = (module.annotationProcessorDependencies + module.kaptDependencies).map { it.file }
        // Artifact metadata is available in memory; unrelated modules must not read source files or JARs.
        if (paths.none { it.name.startsWith("butterknife-compiler-") }) return emptyList()
        val root = File(context.incrementalDataDir, "butterknife/${module.name}/${module.buildVariant}/sources")
        generatedRoots += root.canonicalFile
        val inputs = allCompileFiles.filter { it.file.isFile && it.type in listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin) && generatedRoots.none { root -> it.file.canonicalFile.toPath().startsWith(root.toPath()) } }
        val candidates = inputs.filter { input ->
            val text = analysis.text(input.file)
            ("butterknife" in text) && hasAnnotation(input.file, analysis) ||
                hasPreviousBinding(module, root, text)
        }
        if (candidates.isEmpty()) return emptyList()
        check(paths.any { PROCESSOR in AnnotationProcessorClasspath.names(it) }) { "ButterKnife processor SPI is missing" }
        val sources = ButterKnifeInputs(context, module, analysis).expand(candidates.map { it.file })
        val work = File(context.tempCompileDir, "butterknife/${module.name}")
        val options = (module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty()).filterKeys {
            it == "butterknife.minSdk" || it == "butterknife.debuggable"
        }
        val r2 = ButterKnifeR2(context, module, analysis).prepare(sources, File(work, "r2"))
        val generated = generate(context, module, sources + r2, paths, options, File(work, "processor"))
        val outputs = generated.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        check(outputs.isNotEmpty()) { "ButterKnife no longer generates bindings; run a full Gradle build to remove old bindings" }
        checkNoRemovedBindings(module, root, sources, outputs, analysis)
        val published = publish(outputs, r2, generated, work, root, module)
        val added = sources.filter { source -> inputs.none { it.file.canonicalFile == source.canonicalFile } }.map { source ->
            CompileFile(if (source.extension == "kt") CompileFile.Type.Kotlin else CompileFile.Type.Java, source,
                module.sourceDirs.firstOrNull { source.toPath().startsWith(it.toPath()) } ?: source.parentFile, module)
        }
        companionInputs += added.map { it.file.canonicalFile }
        context.logger.debug("ButterKnife generated ${published.size} bindings for ${sources.size} sources in ${module.name}")
        return published + added
    }

    private fun generate(context: ICompileContext, module: ModuleInfo, sources: List<File>, paths: List<File>,
        options: Map<String, String>, work: File): File {
        val start = System.nanoTime()
        context.logger.info("ButterKnife processor started for ${module.name}.")
        try {
            return if (sources.any { it.extension == "kt" }) {
                AnnotationProcessorRunner().generate(context, module, sources, paths, listOf(PROCESSOR), options,
                    work, isolateKapt = true)
            } else ButterKnifeJavaApt.generate(context, module, sources, paths, options, work)
        } finally {
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            context.logger.info("ButterKnife processor finished for ${module.name} in " +
                    "${String.format(Locale.ROOT, "%.2f", seconds)} seconds")
        }
    }

    private fun publish(outputs: List<File>, r2: List<File>, generated: File, work: File, root: File,
        module: ModuleInfo): List<CompileFile> {
        root.mkdirs()
        return (outputs + r2).map { file ->
            val base = if (file in r2) File(work, "r2") else generated
            val target = File(root, file.relativeTo(base).path)
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
            CompileFile(CompileFile.Type.Java, target, root, module)
        }
    }

    private fun hasAnnotation(file: File, analysis: AptSourceAnalysis): Boolean {
        val facts = analysis.facts(file)
        val names = if (file.extension == "java") analysis.java(file).findAll(AnnotationExpr::class.java).map { it.nameAsString }
            else PsiTreeUtil.collectElementsOfType(analysis.kotlin(file), KtAnnotationEntry::class.java).mapNotNull { it.typeReference?.text }
        return names.any { name ->
            val resolved = facts.imports[name] ?: if ("butterknife" in facts.stars) "butterknife.$name" else name
            resolved.removePrefix("butterknife.") in ANNOTATIONS && resolved.startsWith("butterknife.")
        }
    }

    private fun hasPreviousBinding(module: ModuleInfo, root: File, text: String): Boolean {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty().replace('.', '/')
        val names = TYPE.findAll(text).map { it.groupValues[1] }.toList()
        return (baselineRoots(module) + root).any { base ->
            if (names.any { File(base, "$pkg/${it}_ViewBinding.java").isFile }) return@any true
            // Nested bindings use binary names, e.g. Outer$Inner_ViewBinding.java.
            File(base, pkg).list()?.any { name ->
                name.endsWith("_ViewBinding.java") && '$' in name && name.substringBefore('$') in names
            } == true
        }
    }

    private fun checkNoRemovedBindings(module: ModuleInfo, root: File, sources: List<File>, outputs: List<File>, analysis: AptSourceAnalysis) {
        val names = outputs.map { it.name }.toSet()
        sources.forEach { source ->
            val facts = analysis.facts(source)
            facts.declarations.forEach { type ->
                val path = facts.packageName.replace('.', '/') + "/${type.name}_ViewBinding.java"
                check((baselineRoots(module) + root).none { File(it, path).isFile } || "${type.name}_ViewBinding.java" in names) {
                    "ButterKnife binding removed for ${type.name}; run a full Gradle build"
                }
            }
        }
    }

    companion object {
        const val PROCESSOR = "butterknife.compiler.ButterKnifeProcessor"
        internal val PACKAGE = Regex("(?m)^\\s*package\\s+([\\w.]+)")
        private val TYPE = Regex("\\b(?:class|object)\\s+(\\w+)")
        private val ANNOTATIONS = setOf("BindAnim", "BindArray", "BindBitmap", "BindBool", "BindColor", "BindDimen", "BindDrawable",
            "BindFloat", "BindFont", "BindInt", "BindString", "BindView", "BindViews", "OnCheckedChanged", "OnClick",
            "OnEditorAction", "OnFocusChange", "OnItemClick", "OnItemLongClick", "OnItemSelected", "OnLongClick",
            "OnPageChange", "OnTextChanged", "OnTouch")
        internal fun baselineRoots(module: ModuleInfo): List<File> = listOf(
            File(module.buildPathInfo.generatedSourcePath, "source/kapt/${module.buildVariant}"),
            File(module.buildPathInfo.generatedSourcePath, "ap_generated_sources/${module.buildVariant}/out"))
    }
}
