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

/**
 * Syntax-only const reference candidate.
 *
 * It intentionally does not require a resolved definition. Impact lookup later matches these
 * candidates against changed const definitions and may conservatively over-report effected files.
 */
data class ConstReferenceCandidate(
    val refFilePath: String,
    val packageName: String,
    val constName: String,
    val ownerName: String?,
    val ownerKind: ConstReferenceOwnerKind,
    val importPackages: Set<String> = emptySet(),
)

/**
 * Describes the syntax source that produced a const reference candidate.
 */
enum class ConstReferenceOwnerKind {
    EXPLICIT_CONST_IMPORT,
    EXPLICIT_CLASS_IMPORT,
    PACKAGE_STAR_IMPORT,
    CLASS_STAR_IMPORT,
    OWNER_EXPRESSION,
    BARE_SAME_PACKAGE,
}

internal fun ConstReferenceCandidate.mayReference(definition: ConstDefinition): Boolean {
    if (constName != definition.constName) {
        return false
    }
    return when (ownerKind) {
        ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT,
        ConstReferenceOwnerKind.EXPLICIT_CLASS_IMPORT,
        ConstReferenceOwnerKind.CLASS_STAR_IMPORT,
        ConstReferenceOwnerKind.OWNER_EXPRESSION -> {
            ownerName != null && ownerName in definition.candidateOwnerNames()
        }
        ConstReferenceOwnerKind.PACKAGE_STAR_IMPORT -> definition.packageName in importPackages
        ConstReferenceOwnerKind.BARE_SAME_PACKAGE -> packageName == definition.packageName
    }
}

private fun ConstDefinition.candidateOwnerNames(): Set<String> {
    val simpleName = fqClassName.substringAfterLast('.')
    return linkedSetOf(
        fqClassName,
        simpleName,
        "$fqClassName.Companion",
        "$simpleName.Companion",
        packageName,
    )
}

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

data class ConstReferenceLookupHints(
    val constNames: Set<String>,
    val classConstKeys: Set<Pair<String, String>>,
    val packageConstKeys: Set<Pair<String, String>>,
    val simpleClassNames: Set<String>,
    /**
     * Precise (simpleName, constName) pairs from actual `Owner.CONST` field access expressions.
     * When non-empty, used instead of the cartesian product simpleClassNames × constNames,
     * dramatically reducing the number of DB candidate queries for files with many simple names
     * and many const names.
     */
    val simpleClassConstKeys: Set<Pair<String, String>> = emptySet(),
) {
    fun isEmpty(): Boolean {
        return constNames.isEmpty() &&
            classConstKeys.isEmpty() &&
            packageConstKeys.isEmpty() &&
            simpleClassNames.isEmpty()
    }

    companion object {
        val EMPTY = ConstReferenceLookupHints(
            constNames = emptySet(),
            classConstKeys = emptySet(),
            packageConstKeys = emptySet(),
            simpleClassNames = emptySet(),
        )
    }
}

internal data class OwnerImportContext(
    val explicitClassImports: Map<String, String>,
    val packageAsteriskImports: Set<String>,
)

interface ConstDefinitionLookup {
    fun hasConstName(constName: String): Boolean
    fun hasClass(fqClassName: String): Boolean
    fun hasDefinition(fqClassName: String, constName: String): Boolean
    fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition>
    fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition>
    fun findClassBySimpleName(simpleName: String): Set<String>

    /**
     * Resolves fully-qualified class names by simple name, with the [constName] that triggered
     * the lookup. Tracking implementations use this to record precise (simpleName, constName)
     * pairs for DB candidate queries, avoiding the cartesian product of all simple names
     * with all const names.
     *
     * Default delegates to [findClassBySimpleName] for backward compatibility.
     */
    fun findClassBySimpleNameForConst(simpleName: String, constName: String): Set<String> {
        return findClassBySimpleName(simpleName)
    }
}

class ConstDefinitionIndex(
    definitions: Collection<ConstDefinition> = emptyList(),
) : ConstDefinitionLookup {
    private val definitionsByFilePath = mutableMapOf<String, List<ConstDefinition>>()
    private val definitionsByClassAndConst = mutableMapOf<String, MutableMap<String, MutableList<ConstDefinition>>>()
    private val definitionsByConstName = mutableMapOf<String, MutableList<ConstDefinition>>()
    private val definitionsByPackageAndConst = mutableMapOf<String, MutableMap<String, MutableList<ConstDefinition>>>()
    private val classesBySimpleName = mutableMapOf<String, MutableSet<String>>()
    private val classNames = mutableSetOf<String>()
    private val classDefinitionCount = mutableMapOf<String, Int>()

    init {
        definitions
            .groupBy { it.filePath }
            .forEach { (filePath, fileDefinitions) ->
                replaceFileDefinitions(filePath, fileDefinitions)
            }
    }

    fun replaceFileDefinitions(filePath: String, definitions: List<ConstDefinition>) {
        definitionsByFilePath.remove(filePath)?.forEach { removeDefinition(it) }
        if (definitions.isEmpty()) {
            return
        }
        val normalizedDefinitions = definitions.toList()
        definitionsByFilePath[filePath] = normalizedDefinitions
        normalizedDefinitions.forEach { addDefinition(it) }
    }

    fun removeFileDefinitions(filePath: String) {
        replaceFileDefinitions(filePath, emptyList())
    }

    override fun hasConstName(constName: String): Boolean {
        return definitionsByConstName.containsKey(constName)
    }

    override fun hasClass(fqClassName: String): Boolean {
        return classNames.contains(fqClassName)
    }

    override fun hasDefinition(fqClassName: String, constName: String): Boolean {
        return definitionsByClassAndConst[fqClassName]?.containsKey(constName) == true
    }

    override fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition> {
        return definitionsByClassAndConst[fqClassName]?.get(constName)?.toList().orEmpty()
    }

    override fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition> {
        return definitionsByPackageAndConst[packageName]?.get(constName)?.toList().orEmpty()
    }

    override fun findClassBySimpleName(simpleName: String): Set<String> {
        return classesBySimpleName[simpleName]?.toSet().orEmpty()
    }

    private fun addDefinition(definition: ConstDefinition) {
        definitionsByClassAndConst
            .getOrPut(definition.fqClassName) { mutableMapOf() }
            .getOrPut(definition.constName) { mutableListOf() }
            .add(definition)

        definitionsByConstName
            .getOrPut(definition.constName) { mutableListOf() }
            .add(definition)

        definitionsByPackageAndConst
            .getOrPut(definition.packageName) { mutableMapOf() }
            .getOrPut(definition.constName) { mutableListOf() }
            .add(definition)

        val previousCount = classDefinitionCount[definition.fqClassName] ?: 0
        classDefinitionCount[definition.fqClassName] = previousCount + 1
        if (previousCount == 0) {
            registerClass(definition)
        }
    }

    private fun removeDefinition(definition: ConstDefinition) {
        definitionsByClassAndConst[definition.fqClassName]?.let { constMap ->
            constMap[definition.constName]?.let { definitions ->
                definitions.remove(definition)
                if (definitions.isEmpty()) {
                    constMap.remove(definition.constName)
                }
            }
            if (constMap.isEmpty()) {
                definitionsByClassAndConst.remove(definition.fqClassName)
            }
        }

        definitionsByConstName[definition.constName]?.let { definitions ->
            definitions.remove(definition)
            if (definitions.isEmpty()) {
                definitionsByConstName.remove(definition.constName)
            }
        }

        definitionsByPackageAndConst[definition.packageName]?.let { packageMap ->
            packageMap[definition.constName]?.let { definitions ->
                definitions.remove(definition)
                if (definitions.isEmpty()) {
                    packageMap.remove(definition.constName)
                }
            }
            if (packageMap.isEmpty()) {
                definitionsByPackageAndConst.remove(definition.packageName)
            }
        }

        val currentCount = classDefinitionCount[definition.fqClassName] ?: return
        if (currentCount <= 1) {
            classDefinitionCount.remove(definition.fqClassName)
            unregisterClass(definition)
        } else {
            classDefinitionCount[definition.fqClassName] = currentCount - 1
        }
    }

    private fun registerClass(definition: ConstDefinition) {
        classNames += definition.fqClassName
        val classPart = classPart(definition)
        classesBySimpleName.getOrPut(classPart) { mutableSetOf() } += definition.fqClassName
        classesBySimpleName.getOrPut(classPart.substringAfterLast('.')) { mutableSetOf() } += definition.fqClassName
    }

    private fun unregisterClass(definition: ConstDefinition) {
        classNames.remove(definition.fqClassName)
        val classPart = classPart(definition)
        removeClassSimpleName(classPart, definition.fqClassName)
        removeClassSimpleName(classPart.substringAfterLast('.'), definition.fqClassName)
    }

    private fun removeClassSimpleName(simpleName: String, fqClassName: String) {
        classesBySimpleName[simpleName]?.let { classNames ->
            classNames.remove(fqClassName)
            if (classNames.isEmpty()) {
                classesBySimpleName.remove(simpleName)
            }
        }
    }

    private fun classPart(definition: ConstDefinition): String {
        return if (definition.packageName.isBlank()) {
            definition.fqClassName
        } else {
            definition.fqClassName.removePrefix("${definition.packageName}.")
        }
    }
}

private val ownerTextRegex = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")

internal fun resolveOwnerCandidates(
    ownerText: String,
    constName: String,
    packageName: String,
    importContext: OwnerImportContext,
    definitionIndex: ConstDefinitionLookup,
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
        candidates += definitionIndex.findClassBySimpleNameForConst(ownerText, constName)
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
