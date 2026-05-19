package com.sickworm.intellij.jugg.compiler.constref

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import kotlin.system.measureTimeMillis

class JavaConstParser(
    private val logger: Logger,
) {
    private val parserConfiguration = ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        .setCharacterEncoding(Charsets.UTF_8)
    private val parser = JavaParser(parserConfiguration)

    private val inlineableTypes = setOf(
        "int", "long", "float", "double", "boolean",
        "byte", "short", "char", "String", "java.lang.String",
    )
    private val candidateOwnerTextRegex = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")

    fun parseDefinitions(sourceFile: File): List<ConstDefinition> {
        if (sourceFile.extension != "java" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val compilationUnit = parse(sourceFile) ?: return emptyList()
        val packageName = compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")
        val definitions = mutableListOf<ConstDefinition>()
        compilationUnit.types.forEach { typeDeclaration ->
            collectDefinitions(
                typeDeclaration = typeDeclaration,
                packageName = packageName,
                outerClassName = null,
                sourcePath = sourcePath,
                definitions = definitions,
            )
        }
        return definitions
    }

    fun parseReferences(sourceFile: File, definitionIndex: ConstDefinitionLookup): List<ConstReference> {
        if (sourceFile.extension != "java" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val compilationUnit = parse(sourceFile) ?: return emptyList()
        return parseReferencesFromCu(sourcePath, compilationUnit, definitionIndex)
    }

    fun parseReferenceCandidates(sourceFile: File): List<ConstReferenceCandidate> {
        if (sourceFile.extension != "java" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val compilationUnit = parse(sourceFile) ?: return emptyList()
        return parseReferenceCandidatesFromCu(sourcePath, compilationUnit)
    }

    /**
     * Single-pass variant: parses the file once, traverses the AST twice in the same call stack.
     * First pass collects lookup hints via [TrackingDefinitionLookup]; then calls [definitionIndexFactory]
     * to build the real index; second pass resolves references against it.
     * Avoids the double-parse overhead of the two-call pattern in [ConstRefAnalyzer].
     *
     * @param definitionIndexFactory receives the collected hints and returns the lookup index,
     *        or null to skip reference parsing (no candidates found).
     */
    fun collectHintsAndParseReferences(
        sourceFile: File,
        definitionIndexFactory: (ConstReferenceLookupHints) -> ConstDefinitionLookup?,
    ): List<ConstReference> {
        if (sourceFile.extension != "java" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        var compilationUnit: CompilationUnit?
        val parseMs = measureTimeMillis {
            compilationUnit = parse(sourceFile)
        }
        val cu = compilationUnit ?: return emptyList()

        // Pass 1: collect hints by traversing AST with a tracking (no-op) lookup
        val trackingLookup = TrackingDefinitionLookup()
        var hints: ConstReferenceLookupHints
        val pass1Ms = measureTimeMillis {
            parseReferencesFromCu(sourcePath, cu, trackingLookup)
            hints = trackingLookup.buildHints()
        }
        if (hints.isEmpty()) {
            logger.debug(
                "JavaConstParser collectHintsAndParseReferences timing, " +
                    "file=${sourceFile.name}, parseMs=$parseMs, pass1Ms=$pass1Ms, " +
                    "candidateMs=0, pass2Ms=0, hintsEmpty=true"
            )
            return emptyList()
        }

        // Candidate factory call
        var definitionIndex: ConstDefinitionLookup?
        val candidateMs = measureTimeMillis {
            definitionIndex = definitionIndexFactory(hints)
        }
        if (definitionIndex == null) {
            logger.debug(
                "JavaConstParser collectHintsAndParseReferences timing, " +
                    "file=${sourceFile.name}, parseMs=$parseMs, pass1Ms=$pass1Ms, " +
                    "candidateMs=$candidateMs, pass2Ms=0, noCandidates=true"
            )
            return emptyList()
        }

        // Pass 2: resolve references with the real definition index
        var references: List<ConstReference>
        val pass2Ms = measureTimeMillis {
            references = parseReferencesFromCu(sourcePath, cu, definitionIndex!!)
        }
        val totalMs = parseMs + pass1Ms + candidateMs + pass2Ms
        logger.debug(
            "JavaConstParser collectHintsAndParseReferences timing, " +
                "file=${sourceFile.name}, totalMs=$totalMs, parseMs=$parseMs, " +
                "pass1Ms=$pass1Ms, candidateMs=$candidateMs, pass2Ms=$pass2Ms, " +
                "hintsConstNames=${hints.constNames.size}, " +
                "hintsClassConstKeys=${hints.classConstKeys.size}, " +
                "hintsPackageConstKeys=${hints.packageConstKeys.size}, " +
                "hintsSimpleClassNames=${hints.simpleClassNames.size}, " +
                "refCount=${references.size}"
        )
        return references
    }

    private fun parseReferencesFromCu(
        sourcePath: String,
        compilationUnit: CompilationUnit,
        definitionIndex: ConstDefinitionLookup,
    ): List<ConstReference> {
        val packageName = compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")
        val importContext = buildImportContext(compilationUnit)
        val references = linkedSetOf<ConstReference>()
        val ownerImportContext = OwnerImportContext(
            explicitClassImports = importContext.explicitClassImports,
            packageAsteriskImports = importContext.packageAsteriskImports,
        )

        compilationUnit.findAll(FieldAccessExpr::class.java).forEach { fieldAccess ->
            val constName = fieldAccess.nameAsString
            if (!definitionIndex.hasConstName(constName)) {
                return@forEach
            }
            val ownerText = fieldAccess.scope.toString().trim()
            val resolvedClasses = resolveOwnerCandidates(
                ownerText = ownerText,
                constName = constName,
                packageName = packageName,
                importContext = ownerImportContext,
                definitionIndex = definitionIndex,
            )
            resolvedClasses.forEach { fqClassName ->
                addReference(sourcePath, fqClassName, constName, definitionIndex, references)
            }
        }

        compilationUnit.findAll(NameExpr::class.java).forEach { nameExpr ->
            val constName = nameExpr.nameAsString
            if (!definitionIndex.hasConstName(constName)) {
                return@forEach
            }
            importContext.staticSingleImports[constName].orEmpty().forEach { fqClassName ->
                addReference(sourcePath, fqClassName, constName, definitionIndex, references)
            }
            importContext.staticAsteriskImports.forEach staticImport@{ fqClassName ->
                if (!definitionIndex.hasDefinition(fqClassName, constName)) {
                    return@staticImport
                }
                addReference(sourcePath, fqClassName, constName, definitionIndex, references)
            }
        }

        return references.toList()
    }

    private fun parseReferenceCandidatesFromCu(
        sourcePath: String,
        compilationUnit: CompilationUnit,
    ): List<ConstReferenceCandidate> {
        val packageName = compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")
        val importContext = buildImportContext(compilationUnit)
        val candidates = linkedSetOf<ConstReferenceCandidate>()

        compilationUnit.findAll(FieldAccessExpr::class.java).forEach { fieldAccess ->
            val constName = fieldAccess.nameAsString
            val ownerName = resolveCandidateOwner(fieldAccess.scope.toString().trim(), importContext)
                ?: return@forEach
            candidates += ConstReferenceCandidate(
                refFilePath = sourcePath,
                packageName = packageName,
                constName = constName,
                ownerName = ownerName,
                ownerKind = ConstReferenceOwnerKind.OWNER_EXPRESSION,
            )
        }

        compilationUnit.findAll(NameExpr::class.java).forEach { nameExpr ->
            val constName = nameExpr.nameAsString
            importContext.staticSingleImports[constName].orEmpty().forEach { fqClassName ->
                candidates += ConstReferenceCandidate(
                    refFilePath = sourcePath,
                    packageName = packageName,
                    constName = constName,
                    ownerName = fqClassName,
                    ownerKind = ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT,
                )
            }
            importContext.staticAsteriskImports.forEach { fqClassName ->
                candidates += ConstReferenceCandidate(
                    refFilePath = sourcePath,
                    packageName = packageName,
                    constName = constName,
                    ownerName = fqClassName,
                    ownerKind = ConstReferenceOwnerKind.CLASS_STAR_IMPORT,
                )
            }
        }

        return candidates.toList()
    }

    private fun resolveCandidateOwner(ownerText: String, importContext: JavaImportContext): String? {
        if (!candidateOwnerTextRegex.matches(ownerText) || ownerText == "this" || ownerText == "super") {
            return null
        }
        val firstSegment = ownerText.substringBefore('.')
        val explicitImport = importContext.explicitClassImports[firstSegment]
        if (explicitImport != null) {
            return explicitImport + ownerText.removePrefix(firstSegment)
        }
        return ownerText
    }

    private fun collectDefinitions(
        typeDeclaration: TypeDeclaration<*>,
        packageName: String,
        outerClassName: String?,
        sourcePath: String,
        definitions: MutableList<ConstDefinition>,
    ) {
        val className = typeDeclaration.nameAsString
        val fullClassName = if (outerClassName == null) {
            className
        } else {
            "$outerClassName.$className"
        }
        val fqClassName = if (packageName.isBlank()) {
            fullClassName
        } else {
            "$packageName.$fullClassName"
        }

        typeDeclaration.members
            .filterIsInstance<FieldDeclaration>()
            .forEach { fieldDeclaration ->
                if (!fieldDeclaration.isFinal || (!fieldDeclaration.isStatic && !isInterfaceField(fieldDeclaration, typeDeclaration))) {
                    return@forEach
                }
                val fieldType = fieldDeclaration.commonType.asString()
                if (!inlineableTypes.contains(fieldType)) {
                    return@forEach
                }
                fieldDeclaration.variables.forEach variableLoop@{ variable ->
                    if (!variable.initializer.isPresent) {
                        return@variableLoop
                    }
                    definitions += ConstDefinition(
                        filePath = sourcePath,
                        packageName = packageName,
                        fqClassName = fqClassName,
                        constName = variable.nameAsString,
                        constType = fieldType,
                        constValue = variable.initializer.get().toString(),
                    )
                }
            }

        typeDeclaration.members
            .filterIsInstance<TypeDeclaration<*>>()
            .forEach { nestedType ->
                collectDefinitions(
                    typeDeclaration = nestedType,
                    packageName = packageName,
                    outerClassName = fullClassName,
                    sourcePath = sourcePath,
                    definitions = definitions,
                )
            }
    }

    private fun isInterfaceField(fieldDeclaration: FieldDeclaration, typeDeclaration: TypeDeclaration<*>): Boolean {
        return when (typeDeclaration) {
            is ClassOrInterfaceDeclaration -> typeDeclaration.isInterface && fieldDeclaration.isFinal
            is AnnotationDeclaration -> fieldDeclaration.isFinal
            else -> false
        }
    }

    private fun addReference(
        sourcePath: String,
        fqClassName: String,
        constName: String,
        definitionIndex: ConstDefinitionLookup,
        references: MutableSet<ConstReference>,
    ) {
        definitionIndex.findByClassAndConst(fqClassName, constName)
            .filter { it.filePath != sourcePath }
            .forEach { definition ->
                references += ConstReference(
                    refFilePath = sourcePath,
                    defFqClassName = definition.fqClassName,
                    constName = definition.constName,
                )
            }
    }

    private fun parse(sourceFile: File): CompilationUnit? {
        return try {
            val parseResult = parser.parse(sourceFile)
            if (!parseResult.isSuccessful || !parseResult.result.isPresent) {
                logger.debug("JavaConstParser failed to parse ${sourceFile.path}: ${parseResult.problems}")
                null
            } else {
                parseResult.result.get()
            }
        } catch (t: Throwable) {
            logger.debug("JavaConstParser exception on ${sourceFile.path}: ${t.message}")
            null
        }
    }

    private data class JavaImportContext(
        val explicitClassImports: MutableMap<String, String> = mutableMapOf(),
        val packageAsteriskImports: MutableSet<String> = mutableSetOf(),
        val staticSingleImports: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        val staticAsteriskImports: MutableSet<String> = mutableSetOf(),
    )

    private fun buildImportContext(compilationUnit: CompilationUnit): JavaImportContext {
        val context = JavaImportContext()
        compilationUnit.imports.forEach { importDeclaration ->
            val importName = importDeclaration.nameAsString
            if (importDeclaration.isStatic) {
                if (importDeclaration.isAsterisk) {
                    context.staticAsteriskImports += importName
                } else {
                    val className = importName.substringBeforeLast('.', "")
                    val constName = importName.substringAfterLast('.')
                    if (className.isNotBlank() && constName.isNotBlank()) {
                        context.staticSingleImports.getOrPut(constName) { mutableSetOf() } += className
                    }
                }
            } else {
                if (importDeclaration.isAsterisk) {
                    context.packageAsteriskImports += importName
                } else {
                    val simpleName = importName.substringAfterLast('.')
                    context.explicitClassImports[simpleName] = importName
                }
            }
        }
        return context
    }

    /**
     * Tracks which hints were queried during a no-op AST traversal.
     * Used in the single-pass path to collect DB query candidates without real definitions.
     */
    private class TrackingDefinitionLookup : ConstDefinitionLookup {
        private val constNames = linkedSetOf<String>()
        private val classConstKeys = linkedSetOf<Pair<String, String>>()
        private val packageConstKeys = linkedSetOf<Pair<String, String>>()
        private val simpleClassNames = linkedSetOf<String>()
        private val simpleClassConstKeys = linkedSetOf<Pair<String, String>>()

        override fun hasConstName(constName: String): Boolean {
            val normalized = constName.trim()
            if (normalized.isNotEmpty()) constNames += normalized
            return true
        }

        override fun hasClass(fqClassName: String): Boolean = true

        override fun hasDefinition(fqClassName: String, constName: String): Boolean {
            recordClassConstKey(fqClassName, constName)
            return true
        }

        override fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition> {
            recordClassConstKey(fqClassName, constName)
            return emptyList()
        }

        override fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition> {
            val normalizedPackage = packageName.trim()
            val normalizedConst = constName.trim()
            if (normalizedPackage.isNotEmpty() && normalizedConst.isNotEmpty()) {
                packageConstKeys += normalizedPackage to normalizedConst
            }
            return emptyList()
        }

        override fun findClassBySimpleName(simpleName: String): Set<String> {
            val normalized = simpleName.trim()
            if (normalized.isNotEmpty()) simpleClassNames += normalized
            return emptySet()
        }

        override fun findClassBySimpleNameForConst(simpleName: String, constName: String): Set<String> {
            val normalizedSimple = simpleName.trim()
            val normalizedConst = constName.trim()
            if (normalizedSimple.isNotEmpty()) simpleClassNames += normalizedSimple
            if (normalizedSimple.isNotEmpty() && normalizedConst.isNotEmpty()) {
                simpleClassConstKeys += normalizedSimple to normalizedConst
            }
            return emptySet()
        }

        fun buildHints(): ConstReferenceLookupHints {
            return ConstReferenceLookupHints(
                constNames = constNames,
                classConstKeys = classConstKeys,
                packageConstKeys = packageConstKeys,
                simpleClassNames = simpleClassNames,
                simpleClassConstKeys = simpleClassConstKeys,
            )
        }

        private fun recordClassConstKey(fqClassName: String, constName: String) {
            val normalizedClass = fqClassName.trim()
            val normalizedConst = constName.trim()
            if (normalizedClass.isNotEmpty() && normalizedConst.isNotEmpty()) {
                classConstKeys += normalizedClass to normalizedConst
            }
        }
    }
}
