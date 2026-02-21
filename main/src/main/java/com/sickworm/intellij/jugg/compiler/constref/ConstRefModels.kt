package com.sickworm.intellij.jugg.compiler.constref

import java.io.File

data class ConstDefinition(
    val filePath: String,
    val packageName: String,
    val fqClassName: String,
    val constName: String,
    val constType: String,
    val constValue: String?,
)

data class ConstReference(
    val refFilePath: String,
    val defFqClassName: String,
    val constName: String,
)

data class EffectedConstRef(
    val refFilePath: String,
    val defFqClassName: String,
    val constName: String,
)

data class FileConstParseResult(
    val definitions: List<ConstDefinition>,
    val references: List<ConstReference>,
) {
    companion object {
        val EMPTY = FileConstParseResult(emptyList(), emptyList())
    }
}

internal data class OwnerImportContext(
    val explicitClassImports: Map<String, String>,
    val packageAsteriskImports: Set<String>,
)

class ConstDefinitionIndex(definitions: Collection<ConstDefinition>) {
    private val definitionsByClassAndConst: Map<String, Map<String, List<ConstDefinition>>>
    private val definitionsByConstName: Map<String, List<ConstDefinition>>
    private val definitionsByPackageAndConst: Map<String, Map<String, List<ConstDefinition>>>
    private val classesBySimpleName: Map<String, Set<String>>
    private val classNames: Set<String>

    init {
        definitionsByClassAndConst = definitions
            .groupBy { it.fqClassName }
            .mapValues { (_, defs) -> defs.groupBy { it.constName } }
        definitionsByConstName = definitions.groupBy { it.constName }
        definitionsByPackageAndConst = definitions
            .groupBy { it.packageName }
            .mapValues { (_, defs) -> defs.groupBy { it.constName } }
        classNames = definitions.map { it.fqClassName }.toSet()

        val simpleNameMap = mutableMapOf<String, MutableSet<String>>()
        definitions.forEach { definition ->
            val fqClassName = definition.fqClassName
            val packageName = definition.packageName
            val classPart = if (packageName.isBlank()) {
                fqClassName
            } else {
                fqClassName.removePrefix("$packageName.")
            }
            simpleNameMap.getOrPut(classPart) { mutableSetOf() }.add(fqClassName)
            val leafName = classPart.substringAfterLast('.')
            simpleNameMap.getOrPut(leafName) { mutableSetOf() }.add(fqClassName)
        }
        classesBySimpleName = simpleNameMap
    }

    fun hasConstName(constName: String): Boolean {
        return definitionsByConstName.containsKey(constName)
    }

    fun hasClass(fqClassName: String): Boolean {
        return classNames.contains(fqClassName)
    }

    fun hasDefinition(fqClassName: String, constName: String): Boolean {
        return definitionsByClassAndConst[fqClassName]?.containsKey(constName) == true
    }

    fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition> {
        return definitionsByClassAndConst[fqClassName]?.get(constName).orEmpty()
    }

    fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition> {
        return definitionsByPackageAndConst[packageName]?.get(constName).orEmpty()
    }

    fun findClassBySimpleName(simpleName: String): Set<String> {
        return classesBySimpleName[simpleName].orEmpty()
    }
}

private val ownerTextRegex = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")

internal fun resolveOwnerCandidates(
    ownerText: String,
    constName: String,
    packageName: String,
    importContext: OwnerImportContext,
    definitionIndex: ConstDefinitionIndex,
): Set<String> {
    if (!ownerTextRegex.matches(ownerText)) {
        return emptySet()
    }
    if (ownerText == "this" || ownerText == "super") {
        return emptySet()
    }

    val candidates = linkedSetOf<String>()
    val firstSegment = ownerText.substringBefore('.')
    val explicitImport = importContext.explicitClassImports[firstSegment]
    if (explicitImport != null) {
        val suffix = ownerText.removePrefix(firstSegment)
        candidates += explicitImport + suffix
    }

    if (ownerText.contains('.')) {
        candidates += ownerText
    } else {
        importContext.explicitClassImports[ownerText]?.let { candidates += it }
        candidates += definitionIndex.findClassBySimpleName(ownerText)
    }

    if (packageName.isNotBlank()) {
        candidates += "$packageName.$ownerText"
    }

    importContext.packageAsteriskImports.forEach { importPackage ->
        candidates += "$importPackage.$ownerText"
    }

    return candidates.filterTo(linkedSetOf()) { candidate ->
        definitionIndex.hasDefinition(candidate, constName)
    }
}

internal fun File.toStdPath(): String {
    return absolutePath.replace(File.separatorChar, '/')
}
