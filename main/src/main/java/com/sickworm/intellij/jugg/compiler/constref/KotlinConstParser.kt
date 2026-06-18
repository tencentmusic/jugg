package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Not thread-safe. Callers must serialize access externally.
 * The scheduler enforces this via a shared analysis mutex.
 */
class KotlinConstParser(
    private val logger: Logger,
) {
    private val candidateOwnerTextRegex = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")
    private var disposable: Disposable = Disposer.newDisposable()
    private var environment: KotlinCoreEnvironment
    private var psiFactory: KtPsiFactory

    init {
        environment = buildEnvironment(disposable)
        psiFactory = KtPsiFactory(environment.project, false)
    }

    fun parseDefinitions(sourceFile: File): List<ConstDefinition> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        return try {
            val sourcePath = sourceFile.toStdPath()
            val ktFile = parseKtFile(sourceFile) ?: return emptyList()
            val packageName = readPackageName(ktFile)
            val definitions = mutableListOf<ConstDefinition>()

            ktFile.declarations.filterIsInstance<KtProperty>().forEach { property ->
                if (!property.isVisibleConstDefinition()) {
                    return@forEach
                }
                definitions += ConstDefinition(
                    filePath = sourcePath,
                    packageName = packageName,
                    fqClassName = topLevelClassName(packageName, sourceFile),
                    constName = property.name ?: return@forEach,
                    constType = property.typeReference?.text ?: inferTypeFromInitializer(property.initializer?.text),
                    constValue = property.initializer?.text,
                )
            }

            ktFile.declarations.filterIsInstance<KtClassOrObject>().forEach { classOrObject ->
                collectDefinitionsFromClassOrObject(
                    classOrObject = classOrObject,
                    packageName = packageName,
                    outerClassName = null,
                    sourcePath = sourcePath,
                    definitions = definitions,
                )
            }
            definitions
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser parseDefinitions failed for ${sourceFile.path}: ${t.message}")
            emptyList()
        }
    }

    fun parseReferences(sourceFile: File, definitionIndex: ConstDefinitionLookup): List<ConstReference> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        return try {
            val sourcePath = sourceFile.toStdPath()
            val ktFile = parseKtFile(sourceFile) ?: return emptyList()
            collectReferencesFromKtFile(ktFile, sourcePath, definitionIndex)
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser parseReferences failed for ${sourceFile.path}: ${t.message}")
            emptyList()
        }
    }

    /**
     * Collects syntax-only const reference candidates without consulting known definitions.
     * This keeps reference indexing independent from full-scan definition ordering.
     */
    fun parseReferenceCandidates(sourceFile: File): List<ConstReferenceCandidate> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        return try {
            val sourcePath = sourceFile.toStdPath()
            val ktFile = parseKtFile(sourceFile) ?: return emptyList()
            collectReferenceCandidatesFromKtFile(ktFile, sourcePath)
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser parseReferenceCandidates failed for ${sourceFile.path}: ${t.message}")
            emptyList()
        }
    }

    private fun collectReferenceCandidatesFromKtFile(
        ktFile: KtFile,
        sourcePath: String,
    ): List<ConstReferenceCandidate> {
        val packageName = readPackageName(ktFile)
        val importContext = buildCandidateImportContext(ktFile)
        val candidates = linkedSetOf<ConstReferenceCandidate>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                if (expression.getStrictParentOfType<KtImportDirective>() != null) {
                    super.visitDotQualifiedExpression(expression)
                    return
                }
                val selector = expression.selectorExpression as? KtNameReferenceExpression
                if (selector != null) {
                    val constName = selector.getReferencedName()
                    val ownerText = expression.receiverExpression.text.trim()
                    resolveCandidateOwner(ownerText, importContext)?.let { ownerName ->
                        candidates += ConstReferenceCandidate(
                            refFilePath = sourcePath,
                            packageName = packageName,
                            constName = constName,
                            ownerName = ownerName,
                            ownerKind = ConstReferenceOwnerKind.OWNER_EXPRESSION,
                        )
                    }
                }
                super.visitDotQualifiedExpression(expression)
            }

            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                val nameExpression = expression as? KtNameReferenceExpression ?: run {
                    super.visitReferenceExpression(expression)
                    return
                }
                if (nameExpression.getStrictParentOfType<KtImportDirective>() != null) {
                    super.visitReferenceExpression(expression)
                    return
                }
                val parentDot = nameExpression.parent as? KtDotQualifiedExpression
                if (parentDot != null &&
                    (parentDot.receiverExpression == nameExpression || parentDot.selectorExpression == nameExpression)
                ) {
                    super.visitReferenceExpression(expression)
                    return
                }
                val referenceName = nameExpression.getReferencedName()
                importContext.explicitConstImports[referenceName].orEmpty().forEach { target ->
                    candidates += ConstReferenceCandidate(
                        refFilePath = sourcePath,
                        packageName = packageName,
                        constName = target.constName,
                        ownerName = target.fqClassName,
                        ownerKind = ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT,
                    )
                }
                importContext.classAsteriskImports.forEach { ownerName ->
                    candidates += ConstReferenceCandidate(
                        refFilePath = sourcePath,
                        packageName = packageName,
                        constName = referenceName,
                        ownerName = ownerName,
                        ownerKind = ConstReferenceOwnerKind.CLASS_STAR_IMPORT,
                    )
                }
                importContext.packageAsteriskImports.forEach { importPackage ->
                    candidates += ConstReferenceCandidate(
                        refFilePath = sourcePath,
                        packageName = packageName,
                        constName = referenceName,
                        ownerName = null,
                        ownerKind = ConstReferenceOwnerKind.PACKAGE_STAR_IMPORT,
                        importPackages = setOf(importPackage),
                    )
                }
                candidates += ConstReferenceCandidate(
                    refFilePath = sourcePath,
                    packageName = packageName,
                    constName = referenceName,
                    ownerName = null,
                    ownerKind = ConstReferenceOwnerKind.BARE_SAME_PACKAGE,
                )
                super.visitReferenceExpression(expression)
            }
        })
        return candidates.toList()
    }

    /**
     * Traverses an already-parsed KtFile to collect const references.
     * Extracted so that [parseReferences] and [collectHintsAndParseReferences] can share the
     * visitor logic without parsing the file a second time.
     */
    private fun collectReferencesFromKtFile(
        ktFile: KtFile,
        sourcePath: String,
        definitionIndex: ConstDefinitionLookup,
    ): List<ConstReference> {
        val packageName = readPackageName(ktFile)
        val importContext = buildImportContext(ktFile, definitionIndex)
        val ownerImportContext = OwnerImportContext(
            explicitClassImports = importContext.explicitClassImports,
            packageAsteriskImports = importContext.packageAsteriskImports,
        )

        val references = linkedSetOf<ConstReference>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                val selector = expression.selectorExpression as? KtNameReferenceExpression
                if (selector != null) {
                    val constName = selector.getReferencedName()
                    if (definitionIndex.hasConstName(constName)) {
                        val ownerText = expression.receiverExpression.text.trim()
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
                }
                super.visitDotQualifiedExpression(expression)
            }

            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                val nameExpression = expression as? KtNameReferenceExpression ?: run {
                    super.visitReferenceExpression(expression)
                    return
                }
                if (nameExpression.getStrictParentOfType<KtImportDirective>() != null) {
                    super.visitReferenceExpression(expression)
                    return
                }
                val parentDot = nameExpression.parent as? KtDotQualifiedExpression
                if (parentDot != null && (parentDot.receiverExpression == nameExpression || parentDot.selectorExpression == nameExpression)) {
                    super.visitReferenceExpression(expression)
                    return
                }
                val referenceName = nameExpression.getReferencedName()
                val importedConstTargets = importContext.explicitConstImports[referenceName].orEmpty()
                val hasDirectConst = definitionIndex.hasConstName(referenceName)
                if (!hasDirectConst && importedConstTargets.isEmpty()) {
                    super.visitReferenceExpression(expression)
                    return
                }

                importedConstTargets.forEach { target ->
                    addReference(sourcePath, target.fqClassName, target.constName, definitionIndex, references)
                }
                if (!hasDirectConst) {
                    super.visitReferenceExpression(expression)
                    return
                }
                importContext.classAsteriskImports.forEach { fqClassName ->
                    if (definitionIndex.hasDefinition(fqClassName, referenceName)) {
                        addReference(sourcePath, fqClassName, referenceName, definitionIndex, references)
                    }
                }
                importContext.packageAsteriskImports.forEach { packageNameFromImport ->
                    definitionIndex.findByPackageAndConst(packageNameFromImport, referenceName)
                        .filter { it.fqClassName.endsWith("Kt") }
                        .forEach { definition ->
                            addReference(sourcePath, definition.fqClassName, referenceName, definitionIndex, references)
                        }
                }
                definitionIndex.findByPackageAndConst(packageName, referenceName)
                    .filter { it.fqClassName.endsWith("Kt") }
                    .forEach { definition ->
                        addReference(sourcePath, definition.fqClassName, referenceName, definitionIndex, references)
                    }

                super.visitReferenceExpression(expression)
            }
        })

        return references.toList()
    }
    fun dispose() {
        Disposer.dispose(disposable)
    }

    /**
     * Disposes the current [KotlinCoreEnvironment] and recreates it from scratch.
     * The environment accumulates an internal string-intern table (identifier strings, PSI
     * provider caches) that grows ~200 KB per parsed file and is only released on dispose.
     * Calling this after each analysis batch caps resident heap to roughly
     * batchSize × 200 KB instead of totalFileCount × 200 KB.
     */
    fun resetEnvironment() {
        Disposer.dispose(disposable)
        disposable = Disposer.newDisposable()
        environment = buildEnvironment(disposable)
        psiFactory = KtPsiFactory(environment.project, false)
    }

    /**
     * Drops internal PSI and resolve caches accumulated by the Kotlin compiler environment.
     * Call this after each analysis batch to prevent unbounded heap growth during full scans.
     * Safe to call at any time; subsequent parses will rebuild caches as needed.
     */
    fun dropResolveCaches() {
        PsiManager.getInstance(environment.project).dropResolveCaches()
    }

    private fun buildEnvironment(disposable: Disposable): KotlinCoreEnvironment {
        val configuration = CompilerConfiguration().also {
            it.put(CommonConfigurationKeys.MODULE_NAME, "jugg-const-ref")
            it.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        return KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    /**
     * Collects lookup hints and parses references in a single KtFile parse pass.
     * Use instead of calling [collectReferenceLookupHints] + [parseReferences] separately
     * to halve the number of KtFile allocations per file during Phase 2.
     *
     * @param definitionIndexFactory called with the collected hints to build the lookup index.
     *        The factory may return null if no candidate definitions are found (hints empty).
     * @return the parsed references, or empty list if no hints or no candidates.
     */
    fun collectHintsAndParseReferences(
        sourceFile: File,
        definitionIndexFactory: (ConstReferenceLookupHints) -> ConstDefinitionLookup?,
    ): List<ConstReference> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        return try {
            collectHintsAndParseReferencesInternal(sourceFile, definitionIndexFactory)
        } catch (t: Throwable) {
            logger.debug(
                "KotlinConstParser collectHintsAndParseReferences failed for ${sourceFile.path}: ${t.message}"
            )
            emptyList()
        }
    }

    private fun collectHintsAndParseReferencesInternal(
        sourceFile: File,
        definitionIndexFactory: (ConstReferenceLookupHints) -> ConstDefinitionLookup?,
    ): List<ConstReference> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        var ktFile: KtFile? = null
        val parseMs = measureTimeMillis {
            ktFile = parseKtFile(sourceFile)
        }
        val parsedFile = ktFile ?: return emptyList()

        // Collect hints by replaying the visitor with a no-op lookup that tracks accesses.
        val trackingLookup = HintsTrackingLookup()
        var hints: ConstReferenceLookupHints
        val pass1Ms = measureTimeMillis {
            collectReferencesFromKtFile(parsedFile, sourcePath, trackingLookup)
            hints = trackingLookup.buildHints()
        }
        if (hints.isEmpty()) {
            logger.debug(
                "KotlinConstParser collectHintsAndParseReferences timing, " +
                    "file=${sourceFile.name}, parseMs=$parseMs, pass1Ms=$pass1Ms, " +
                    "candidateMs=0, pass2Ms=0, hintsEmpty=true"
            )
            return emptyList()
        }

        // Build the real index using caller-supplied candidates.
        var definitionIndex: ConstDefinitionLookup?
        val candidateMs = measureTimeMillis {
            definitionIndex = definitionIndexFactory(hints)
        }
        if (definitionIndex == null) {
            logger.debug(
                "KotlinConstParser collectHintsAndParseReferences timing, " +
                    "file=${sourceFile.name}, parseMs=$parseMs, pass1Ms=$pass1Ms, " +
                    "candidateMs=$candidateMs, pass2Ms=0, noCandidates=true"
            )
            return emptyList()
        }

        // Re-use the same KtFile to parse references — no second file read.
        var references: List<ConstReference>
        val pass2Ms = measureTimeMillis {
            references = collectReferencesFromKtFile(parsedFile, sourcePath, definitionIndex!!)
        }
        val totalMs = parseMs + pass1Ms + candidateMs + pass2Ms
        logger.debug(
            "KotlinConstParser collectHintsAndParseReferences timing, " +
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

    private fun collectDefinitionsFromClassOrObject(
        classOrObject: KtClassOrObject,
        packageName: String,
        outerClassName: String?,
        sourcePath: String,
        definitions: MutableList<ConstDefinition>,
    ) {
        val name = classOrObject.name ?: return
        val className = if (outerClassName == null) {
            name
        } else {
            "$outerClassName.$name"
        }
        val fqClassName = if (packageName.isBlank()) className else "$packageName.$className"

        classOrObject.declarations.filterIsInstance<KtProperty>().forEach { property ->
            if (!property.isVisibleConstDefinition()) {
                return@forEach
            }
            definitions += ConstDefinition(
                filePath = sourcePath,
                packageName = packageName,
                fqClassName = fqClassName,
                constName = property.name ?: return@forEach,
                constType = property.typeReference?.text ?: inferTypeFromInitializer(property.initializer?.text),
                constValue = property.initializer?.text,
            )
        }

        if (classOrObject is KtClass) {
            classOrObject.companionObjects.forEach { companion ->
                companion.declarations.filterIsInstance<KtProperty>().forEach companionProperty@{ property ->
                    if (!property.isVisibleConstDefinition()) {
                        return@companionProperty
                    }
                    definitions += ConstDefinition(
                        filePath = sourcePath,
                        packageName = packageName,
                        fqClassName = fqClassName,
                        constName = property.name ?: return@companionProperty,
                        constType = property.typeReference?.text ?: inferTypeFromInitializer(property.initializer?.text),
                        constValue = property.initializer?.text,
                    )
                }
            }
        }

        classOrObject.declarations.filterIsInstance<KtClassOrObject>()
            .filterNot { it is KtObjectDeclaration && it.isCompanion() }
            .forEach { nested ->
                collectDefinitionsFromClassOrObject(
                    classOrObject = nested,
                    packageName = packageName,
                    outerClassName = className,
                    sourcePath = sourcePath,
                    definitions = definitions,
                )
            }
    }

    private fun KtProperty.isVisibleConstDefinition(): Boolean {
        return hasModifier(KtTokens.CONST_KEYWORD) && !hasModifier(KtTokens.PRIVATE_KEYWORD)
    }

    private fun parseKtFile(sourceFile: File): KtFile? {
        return try {
            psiFactory.createFile(sourceFile.name, sourceFile.readText())
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser failed to parse ${sourceFile.path}: ${t.message}")
            null
        }
    }

    private fun topLevelClassName(packageName: String, sourceFile: File): String {
        val className = "${sourceFile.nameWithoutExtension}Kt"
        return if (packageName.isBlank()) className else "$packageName.$className"
    }

    // Lightweight fallback when type reference is absent. Precision is not required for const-ref matching.
    private fun inferTypeFromInitializer(initializer: String?): String {
        if (initializer == null) {
            return ""
        }
        return when {
            initializer.startsWith("\"") -> "String"
            initializer.endsWith("L") -> "Long"
            initializer.endsWith("f", ignoreCase = true) -> "Float"
            initializer.contains('.') -> "Double"
            initializer == "true" || initializer == "false" -> "Boolean"
            initializer.startsWith('\'') && initializer.endsWith('\'') -> "Char"
            else -> "Int"
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

    private data class KotlinImportContext(
        val explicitClassImports: MutableMap<String, String> = mutableMapOf(),
        val packageAsteriskImports: MutableSet<String> = mutableSetOf(),
        val classAsteriskImports: MutableSet<String> = mutableSetOf(),
        val explicitConstImports: MutableMap<String, MutableSet<ImportedConstTarget>> = mutableMapOf(),
    )

    private data class ImportedConstTarget(
        val fqClassName: String,
        val constName: String,
    )

    private data class CandidateImportContext(
        val explicitClassImports: MutableMap<String, String> = mutableMapOf(),
        val packageAsteriskImports: MutableSet<String> = mutableSetOf(),
        val classAsteriskImports: MutableSet<String> = mutableSetOf(),
        val explicitConstImports: MutableMap<String, MutableSet<ImportedConstTarget>> = mutableMapOf(),
    )

    private fun readPackageName(ktFile: KtFile): String {
        return try {
            ktFile.packageFqName.asString()
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser failed to read package name: ${t.message}")
            ""
        }
    }

    private fun readImportDirectives(ktFile: KtFile): List<KtImportDirective> {
        return try {
            ktFile.importDirectives
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser failed to read import directives: ${t.message}")
            emptyList()
        }
    }

    private fun buildCandidateImportContext(ktFile: KtFile): CandidateImportContext {
        val context = CandidateImportContext()
        readImportDirectives(ktFile).forEach { importDirective ->
            val importedFqName = importDirective.importedFqName?.asString() ?: return@forEach
            val aliasName = importDirective.aliasName
            if (importDirective.isAllUnder) {
                context.packageAsteriskImports += importedFqName
                context.classAsteriskImports += importedFqName
                return@forEach
            }
            val importedName = importedFqName.substringAfterLast('.')
            val bindName = aliasName ?: importedName
            context.explicitClassImports[bindName] = importedFqName

            val owner = importedFqName.substringBeforeLast('.', "")
            if (owner.isNotBlank()) {
                context.explicitConstImports.getOrPut(bindName) { mutableSetOf() } += ImportedConstTarget(
                    fqClassName = owner,
                    constName = importedName,
                )
            }
        }
        return context
    }

    private fun resolveCandidateOwner(ownerText: String, importContext: CandidateImportContext): String? {
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

    private fun buildImportContext(ktFile: KtFile, definitionIndex: ConstDefinitionLookup): KotlinImportContext {
        val context = KotlinImportContext()
        readImportDirectives(ktFile).forEach { importDirective ->
            val importedFqName = importDirective.importedFqName?.asString() ?: return@forEach
            val aliasName = importDirective.aliasName
            if (importDirective.isAllUnder) {
                if (definitionIndex.hasClass(importedFqName)) {
                    context.classAsteriskImports += importedFqName
                } else {
                    context.packageAsteriskImports += importedFqName
                }
                return@forEach
            }
            val importedName = importedFqName.substringAfterLast('.')
            val bindName = aliasName ?: importedName
            if (definitionIndex.hasClass(importedFqName)) {
                context.explicitClassImports[bindName] = importedFqName
                return@forEach
            }

            val owner = importedFqName.substringBeforeLast('.', "")
            if (owner.isNotBlank() && definitionIndex.hasDefinition(owner, importedName)) {
                context.explicitConstImports.getOrPut(bindName) { mutableSetOf() } += ImportedConstTarget(
                    fqClassName = owner,
                    constName = importedName,
                )
                return@forEach
            }

            if (owner.isNotBlank()) {
                definitionIndex.findByPackageAndConst(owner, importedName).forEach { definition ->
                    context.explicitConstImports.getOrPut(bindName) { mutableSetOf() } += ImportedConstTarget(
                        fqClassName = definition.fqClassName,
                        constName = importedName,
                    )
                }
            }
        }
        return context
    }

    /**
     * A [ConstDefinitionLookup] that records every lookup key without requiring real definitions.
     * Used in [collectHintsAndParseReferences] to drive the visitor pass that collects
     * [ConstReferenceLookupHints] from the parsed KtFile.
     */
    private class HintsTrackingLookup : ConstDefinitionLookup {
        private val constNames = linkedSetOf<String>()
        private val classConstKeys = linkedSetOf<Pair<String, String>>()
        private val packageConstKeys = linkedSetOf<Pair<String, String>>()
        private val simpleClassNames = linkedSetOf<String>()
        private val simpleClassConstKeys = linkedSetOf<Pair<String, String>>()

        override fun hasConstName(constName: String): Boolean {
            val n = constName.trim()
            if (n.isNotEmpty()) constNames += n
            return true
        }

        override fun hasClass(fqClassName: String): Boolean = true

        override fun hasDefinition(fqClassName: String, constName: String): Boolean {
            record(fqClassName, constName)
            return true
        }

        override fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition> {
            record(fqClassName, constName)
            return emptyList()
        }

        override fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition> {
            val p = packageName.trim()
            val n = constName.trim()
            if (p.isNotEmpty() && n.isNotEmpty()) packageConstKeys += p to n
            return emptyList()
        }

        override fun findClassBySimpleName(simpleName: String): Set<String> {
            val s = simpleName.trim()
            if (s.isNotEmpty()) simpleClassNames += s
            return emptySet()
        }

        override fun findClassBySimpleNameForConst(simpleName: String, constName: String): Set<String> {
            val s = simpleName.trim()
            val n = constName.trim()
            if (s.isNotEmpty()) simpleClassNames += s
            if (s.isNotEmpty() && n.isNotEmpty()) simpleClassConstKeys += s to n
            return emptySet()
        }

        fun buildHints() = ConstReferenceLookupHints(
            constNames = constNames,
            classConstKeys = classConstKeys,
            packageConstKeys = packageConstKeys,
            simpleClassNames = simpleClassNames,
            simpleClassConstKeys = simpleClassConstKeys,
        )

        private fun record(fqClassName: String, constName: String) {
            val c = fqClassName.trim()
            val n = constName.trim()
            if (c.isNotEmpty() && n.isNotEmpty()) classConstKeys += c to n
        }
    }
}
