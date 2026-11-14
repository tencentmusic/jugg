package com.sickworm.intellij.jugg.compiler.source.apt

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.constref.ConstDefinition
import com.sickworm.intellij.jugg.compiler.constref.JavaConstParser
import com.sickworm.intellij.jugg.compiler.constref.KotlinConstParser
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.io.File

/** Read-only, lazy source snapshots shared by built-in processors for one module round. */
class AptSourceAnalysis(val files: List<CompileFile>) {
    data class Annotation(val name: String, val arguments: Map<String, String>, val target: String? = null)
    data class Field(val name: String, val type: String, val annotations: List<Annotation>)
    data class Declaration(val name: String, val parents: List<String>, val annotations: List<Annotation>, val fields: List<Field>, val interfaces: List<String>)
    data class Facts(val packageName: String, val imports: Map<String, String>, val stars: List<String>,
        val declarations: List<Declaration>, val kotlin: Boolean)

    private val timingNanos = linkedMapOf<String, Long>()
    private val diagnosticCounts = linkedMapOf<String, Long>()

    internal fun recordTime(phase: String, nanos: Long) {
        timingNanos[phase] = (timingNanos[phase] ?: 0L) + nanos
    }

    internal fun count(name: String, amount: Long = 1L) {
        diagnosticCounts[name] = (diagnosticCounts[name] ?: 0L) + amount
    }

    // Timings are inclusive: parent phases contain their nested parsing and lookup work.
    internal fun timingSummary(): String =
        "inclusiveMs=${timingNanos.mapValues { it.value / 1_000_000.0 }}, counts=$diagnosticCounts"

    private inline fun <T> measured(phase: String, action: () -> T): T {
        val start = System.nanoTime()
        try {
            return action()
        } finally {
            recordTime(phase, System.nanoTime() - start)
            count(phase + "Calls")
        }
    }

    private val contents = mutableMapOf<String, Result<String>>()
    private val javaTrees = mutableMapOf<String, Result<CompilationUnit>>()
    private val kotlinTrees = mutableMapOf<String, Result<KtFile>>()
    private val sourceFacts = mutableMapOf<String, Result<Facts>>()
    private val constantDefinitions = mutableMapOf<String, List<ConstDefinition>>()
    private val parser by lazy { JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)) }

    fun text(file: File): String = contents.getOrPut(file.canonicalPath) { runCatching { measured("sourceRead") { file.readText() } } }.getOrThrow()
    fun mentions(file: File, packageName: String): Boolean = text(file).contains(packageName)
    fun java(file: File): CompilationUnit = javaTrees.getOrPut(file.canonicalPath) {
        runCatching {
            val result = measured("javaParse") { parser.parse(text(file)) }
            check(result.isSuccessful) { "Cannot parse $file: ${result.problems}" }
            result.result.orElseThrow { IllegalStateException("No Java tree for $file") }
        }
    }.getOrThrow()
    fun kotlin(file: File): KtFile = kotlinTrees.getOrPut(file.canonicalPath) {
        runCatching {
            // Normalize only the read-only tree; rewrite processors need offsets in the original text.
            val content = text(file).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            val tree = measured("kotlinParse") { BaseKotlinJuggAptProcessor.parseSnapshot(file.name, content) }
            val parseError = PsiTreeUtil.findChildOfType(tree, PsiErrorElement::class.java)
            if (parseError != null) {
                throw IllegalStateException("Cannot parse $file at offset ${parseError.textOffset}: ${parseError.errorDescription}")
            }
            tree
        }
    }.getOrThrow()
    fun facts(file: File): Facts = sourceFacts.getOrPut(file.canonicalPath) {
        runCatching { measured("sourceFacts") { if (file.extension == "java") javaFacts(file) else kotlinFacts(file) } }
    }.getOrThrow()
    fun definitions(file: File): List<ConstDefinition> = constantDefinitions.getOrPut(file.canonicalPath) {
        if (file.extension == "java") JavaConstParser.definitions(file, java(file), includePrivate = true) else KotlinConstParser.definitions(file, kotlin(file), includePrivate = true)
    }

    /** A processor may rewrite a file; subsequent consumers must observe its new content. */
    fun invalidate(file: File) {
        val path = file.canonicalPath
        contents.remove(path)
        javaTrees.remove(path)
        kotlinTrees.remove(path)
        sourceFacts.remove(path)
        constantDefinitions.remove(path)
    }

    private fun javaFacts(file: File): Facts {
        val tree = java(file)
        val imports = tree.imports.filterNot { it.isAsterisk }.associate { it.name.identifier to it.nameAsString }
        val declarations = tree.findAll(ClassOrInterfaceDeclaration::class.java).map { type ->
            Declaration(javaName(type), (type.extendedTypes + type.implementedTypes).map { it.asString() },
                type.annotations.map(::javaAnnotation), type.fields.flatMap { field ->
                    field.variables.map { Field(it.nameAsString, it.type.asString(), field.annotations.map(::javaAnnotation)) }
                }, type.implementedTypes.map { it.asString() })
        }
        return Facts(tree.packageDeclaration.map { it.nameAsString }.orElse(""), imports,
            tree.imports.filter { it.isAsterisk }.map { it.nameAsString }, declarations, false)
    }

    private fun javaName(type: TypeDeclaration<*>): String {
        val outer = type.findAncestor(TypeDeclaration::class.java).orElse(null)
        return if (outer == null) type.nameAsString else javaName(outer) + "$" + type.nameAsString
    }

    private fun javaAnnotation(annotation: AnnotationExpr): Annotation {
        val arguments = when {
            annotation.isNormalAnnotationExpr -> annotation.asNormalAnnotationExpr().pairs.associate { it.nameAsString to it.value.toString() }
            annotation.isSingleMemberAnnotationExpr -> mapOf("value" to annotation.asSingleMemberAnnotationExpr().memberValue.toString())
            else -> emptyMap()
        }
        return Annotation(annotation.nameAsString, arguments)
    }

    private fun kotlinFacts(file: File): Facts {
        val tree = kotlin(file)
        val imports = tree.importDirectives.filterNot { it.isAllUnder }.associate {
            (it.aliasName ?: it.importedFqName!!.shortName().asString()) to it.importedFqName!!.asString()
        }
        val classes = PsiTreeUtil.collectElementsOfType(tree, KtClassOrObject::class.java)
        val declarations = classes.filter { it.name != null && !(it is KtObjectDeclaration && it.isCompanion()) }.map { type ->
            Declaration(kotlinName(type), type.superTypeListEntries.map { it.typeReference?.text ?: error("Missing supertype in $file") },
                type.annotationEntries.map(::kotlinAnnotation), type.declarations.filterIsInstance<KtProperty>().map { property ->
                    Field(property.name ?: error("Unnamed field in $file"), property.typeReference?.text.orEmpty(),
                        property.annotationEntries.map(::kotlinAnnotation))
                }, type.superTypeListEntries.filterNot { it is KtSuperTypeCallEntry }.map { it.typeReference!!.text })
        }
        return Facts(tree.packageFqName.asString(), imports,
            tree.importDirectives.filter { it.isAllUnder }.map { it.importedFqName!!.asString() }, declarations, true)
    }

    private fun kotlinName(type: KtClassOrObject): String {
        val outer = type.getStrictParentOfType<KtClassOrObject>()
        val name = type.name ?: error("Anonymous class cannot contribute annotation facts")
        return if (outer == null) name else kotlinName(outer) + "$" + name
    }

    private fun kotlinAnnotation(annotation: KtAnnotationEntry): Annotation = Annotation(
        annotation.typeReference!!.text,
        annotation.valueArguments.mapIndexed { index, argument ->
            (argument.getArgumentName()?.asName?.asString() ?: "#$index") to
                (argument.getArgumentExpression()?.text ?: error("Missing annotation value"))
        }.toMap(), annotation.useSiteTarget?.text?.removePrefix("@").orEmpty().trimEnd(':'),
    )
}
