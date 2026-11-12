package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.AptSourceAnalysis
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Reads compiled inheritance only after a round has a confirmed binding candidate. */
internal class ButterKnifeInputs(private val context: ICompileContext, private val module: ModuleInfo,
    private val analysis: AptSourceAnalysis) {
    fun expand(sources: List<File>): List<File> {
        val changedTypes = sources.flatMap { file ->
            val facts = analysis.facts(file)
            facts.declarations.map { if (facts.packageName.isEmpty()) it.name else "${facts.packageName}.${it.name}" }
        }.map { it.replace('.', '/') }.toMutableSet()
        val nodes = context.modules.values.flatMap { owner ->
            owner.buildPathInfo.allClassPath.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "class" }.map { file ->
                    val node = ClassNode()
                    ClassReader(file.readBytes()).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
                    owner to node
                }.toList()
            }
        }.distinctBy { it.second.name }
        val result = sources.toMutableList()
        var found: Boolean
        do {
            found = false
            nodes.filter { it.second.superName in changedTypes && it.second.name !in changedTypes }.forEach { (owner, node) ->
                changedTypes += node.name
                found = true
                if (hasBinding(node)) {
                    check(owner.name == module.name) { "ButterKnife inheritance affects module ${owner.name}; run a full Gradle build" }
                    val relative = node.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + node.sourceFile
                    val source = owner.sourceDirs.map { File(it, relative) }.firstOrNull { it.isFile }
                        ?: error("Cannot locate ButterKnife descendant ${node.name}")
                    result += source
                }
            }
        } while (found)
        return result.distinctBy { it.canonicalPath }
    }

    private fun hasBinding(node: ClassNode): Boolean = node.fields.any { field ->
        (field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty()).any { it.desc.startsWith("Lbutterknife/") }
    } || node.methods.any { method ->
        (method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()).any { it.desc.startsWith("Lbutterknife/") }
    }
}
