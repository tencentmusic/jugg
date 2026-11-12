package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.AptSemanticResolver
import com.sickworm.intellij.jugg.compiler.source.apt.AptSourceAnalysis
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Extends only referenced R2 namespaces with compile-time placeholders; runtime code uses R. */
internal class ButterKnifeR2(private val context: ICompileContext, private val module: ModuleInfo,
    private val analysis: AptSourceAnalysis) {
    fun prepare(sources: List<File>, directory: File): List<File> {
        val references = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        sources.forEach { source ->
            val text = analysis.text(source)
            if ("R2" !in text) return@forEach
            val facts = analysis.facts(source)
            RESOURCE.findAll(text).forEach reference@{ match ->
                val qualifier = match.groupValues[1]
                val fq = facts.imports[qualifier] ?: if (qualifier == "R2") "${facts.packageName}.R2" else qualifier
                if (!fq.endsWith(".R2")) return@reference
                references.getOrPut(fq) { linkedMapOf() }.getOrPut(match.groupValues[2]) { linkedSetOf() }.add(match.groupValues[3])
            }
        }
        if (references.isEmpty()) return emptyList()
        val resolver = AptSemanticResolver(context, module, analysis)
        return references.map { (fq, types) ->
            val pkg = fq.removeSuffix(".R2")
            val all = linkedMapOf<String, MutableMap<String, Int>>()
            val outer = resolver.classInfo(fq)
            outer?.innerClasses.orEmpty().filter { it.outerName == fq.replace('.', '/') }.forEach { inner ->
                val node = resolver.classInfo(inner.name.replace('/', '.')) ?: error("Cannot read R2 type ${inner.name}")
                all[inner.innerName] = node.fields.filter { it.value is Int }.associate { it.name to (it.value as Int) }.toMutableMap()
            }
            var next = all.values.flatMap { it.values }.maxOrNull() ?: 0
            types.forEach { (type, names) ->
                val fields = all.getOrPut(type) { linkedMapOf() }
                names.forEach { name -> if (name !in fields) fields[name] = ++next }
            }
            File(directory, "${pkg.replace('.', '/')}/R2.java").apply {
                parentFile.mkdirs()
                writeText("package $pkg;\npublic final class R2 {\n" + all.entries.joinToString("\n") { (type, fields) ->
                    "public static final class $type {\n" + fields.entries.joinToString("\n") { (name, value) ->
                        "public static final int $name = $value;"
                    } + "\n}"
                } + "\n}\n")
            }
        }
    }

    companion object {
        private val RESOURCE = Regex("\\b((?:[A-Za-z_]\\w*\\.)*[A-Za-z_]\\w*)\\.(\\w+)\\.(\\w+)")
    }
}
