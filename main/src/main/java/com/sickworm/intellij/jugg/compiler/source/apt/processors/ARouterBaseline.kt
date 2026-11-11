package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.AptSourceAnalysis
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Associates round sources with current business classes, keeping lookups within this round. */
internal class ARouterBaseline(private val module: ModuleInfo, private val analysis: AptSourceAnalysis) {
    private val roots = module.buildPathInfo.allClassPath.filter { it.isDirectory }.distinctBy { it.canonicalPath }
    private val nodes = mutableMapOf<File, ClassNode>()
    private val packages = mutableMapOf<String, List<ClassNode>>()
    private val allClasses by lazy {
        analysis.count("baselineFullScans")
        roots.flatMap { readClasses(it) }.distinctBy { it.name }
    }

    /** Exact declarations take precedence over same-package SourceFile and package-move recovery. */
    fun classes(file: File): List<ClassNode> {
        val timingStart = System.nanoTime()
        try {
            val facts = analysis.facts(file)
            val identities = facts.declarations.map {
                (if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}").replace('.', '/')
            }.toSet()
            val local = packages.getOrPut(facts.packageName) {
                roots.flatMap { root -> readClasses(File(root, facts.packageName.replace('.', '/')), recursive = false) }
                    .distinctBy { it.name }
            }
            val matched = local.filter { it.name in identities || it.sourceFile == file.name }
            if (matched.isNotEmpty()) return matched
            if (!analysis.mentions(file, ARouterInputSemantics.PACKAGE)) return emptyList()
            analysis.count("baselinePackageMoveScans")
            val elsewhere = allClasses.filter { it.name in identities || it.sourceFile == file.name }
            // SourceFile has no package identity; guessing across packages could compare unrelated annotations.
            check(elsewhere.map { it.name.substringBeforeLast('/', "") }.distinct().size <= 1) {
                "Ambiguous SourceFile identity ${file.name}"
            }
            return elsewhere
        } finally {
            analysis.recordTime("baselineAssociation", System.nanoTime() - timingStart)
        }
    }

    /** Missing outputs of an established module must not be mistaken for a newly added declaration. */
    fun requireReliableAssociation(context: ICompileContext, file: File, old: List<ClassNode>, current: Map<String, String>) {
        val facts = analysis.facts(file)
        val identities = facts.declarations.map {
            if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}"
        }
        val missing = identities.filter { name -> old.none { it.name.replace('/', '.') == name } }
        if (missing.isEmpty() || current.isEmpty()) return
        val descriptors = missing.map { "L${it.replace('.', '/')};" }
        check(context.containsApkClass(descriptors).none { it.className in descriptors }) {
            "Business classes are missing for existing declarations in $file: $missing"
        }
        if (old.isNotEmpty() || allClasses.isNotEmpty()) return
        check(context.getGeneratedSourcePaths(module).none { it.exists() }) {
            "Business class outputs are missing for $file; cannot prove a new declaration"
        }
    }

    private fun readClasses(root: File, recursive: Boolean = true): List<ClassNode> {
        val timingStart = System.nanoTime()
        try {
            if (!root.exists()) return emptyList()
            check(root.isDirectory && root.canRead()) { "Class directory is not readable: $root" }
            val files = if (recursive) root.walkTopDown().onEnter { directory ->
                check(directory.canRead() && directory.listFiles() != null) { "Class directory is not readable: $directory" }
                true
            }.onFail { file, cause ->
                throw IllegalStateException("Cannot read class directory $file", cause)
            }.filter { it.isFile && it.extension == "class" }.toList() else
                (root.listFiles() ?: error("Cannot read class directory $root")).filter { it.isFile && it.extension == "class" }
            return files.map { file -> nodes.getOrPut(file.canonicalFile) {
                val bytes = file.readBytes()
                analysis.count("baselineClassReads")
                analysis.count("baselineClassBytes", bytes.size.toLong())
                ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) }
            } }
        } finally {
            analysis.recordTime("baselineReadClasses", System.nanoTime() - timingStart)
        }
    }
}
